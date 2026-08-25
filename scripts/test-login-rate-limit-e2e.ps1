<#
.SYNOPSIS
    登入限流（POST /api/auth/login）的真實 SQL Server opt-in E2E 驗證腳本。

.DESCRIPTION
    比照 test-password-management-e2e.ps1 的既有慣例：預設（不帶 -ConfirmTestDatabase）
    只讀取 .env、解析出將要連線的 DB 資訊、印出來給你確認，然後直接結束——不建立任何連線、
    不呼叫任何 API、不寫入任何資料。要真的執行完整驗證，必須明確加上 -ConfirmTestDatabase。

    所有測試帳號的 Email 都帶同一個本次執行專屬的隨機字串前綴（login-rl-e2e-<runId>-...），
    腳本結束前（不論成功或失敗）一律在 finally 區塊清掉自己建立的資料，絕不觸碰任何其他帳號。

    驗證的是 AuthController 的兩層限流（門檻與忘記密碼一致，未自行放寬）：
      - email 層（主要防線）：同一 email 15 分鐘內第 6 次失敗嘗試回 429
      - IP 層（次要、較粗略的防線）：同一來源 IP 每分鐘 10 次、每小時 30 次

    本機測試的 email 層與 IP 層案例全部從同一支 IP 發出，且 IP 層門檻（10/分鐘）比
    email 層案例本身需要的呼叫次數還低，所以腳本中段安排了兩次 65 秒等待，讓 IP 層
    1 分鐘滑動視窗在切換場景前先清空，避免前一段的呼叫次數提前觸發 IP 層、干擾下一段
    email 層案例的判讀。腳本最後一段刻意用不同（不存在的）Email 連續發出請求直到
    觸發 IP 層本身的 429，驗證 IP 層與 email 層是各自獨立判斷的兩道防線——所以完整跑
    一次大約需要 3 分鐘（含兩次等待）。

    注意：IP 層的計數器活在執行中的後端程式記憶體裡，不會因為這支腳本結束而重置。
    單次執行本身就會把 IP-hourly（30）用掉超過八成，同一小時內重複執行很容易讓後面
    的案例被 IP 層擋下而失敗——這不是限流邏輯本身的錯誤，重新啟動後端或等待該小時
    視窗過期即可恢復。

.PARAMETER BaseUrl
    後端 API 的 base URL。刻意要求是 localhost／127.0.0.1，避免不小心打到別的環境。

.PARAMETER ConfirmTestDatabase
    唯一能讓腳本真的執行寫入操作的開關。沒有這個參數，腳本只會做唯讀的連線資訊預覽。

.PARAMETER EnvFile
    .env 檔案路徑，預設是這支腳本所在目錄的上一層（專案根目錄）。

.EXAMPLE
    # 只看看會連到哪個 DB，不執行任何動作
    .\test-login-rate-limit-e2e.ps1

.EXAMPLE
    # 確認過上面印出的 DB 資訊之後，真的執行
    .\test-login-rate-limit-e2e.ps1 -ConfirmTestDatabase
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$ConfirmTestDatabase,
    [string]$EnvFile = ""
)

$ErrorActionPreference = "Stop"

try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # 某些非互動式主控台不允許設定 OutputEncoding，失敗就算了，純粹是顯示美觀問題
}

if ([string]::IsNullOrEmpty($EnvFile)) {
    $EnvFile = Join-Path (Split-Path -Parent $PSCommandPath) "..\.env"
}

# ============================================================================
# 共用工具
# ============================================================================

function New-RunId {
    return ([Guid]::NewGuid().ToString("N")).Substring(0, 10)
}

function Read-DotEnv {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw ".env 檔案不存在：$Path（腳本需要 DB_URL／DB_USERNAME／DB_PASSWORD 才能連線）"
    }
    $vars = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq "" -or $line.StartsWith("#")) { return }
        if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $key = $matches[1]
            $value = $matches[2].Trim()
            if ($value.Length -ge 2 -and (
                    ($value.StartsWith('"') -and $value.EndsWith('"')) -or
                    ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $vars[$key] = $value
        }
    }
    return $vars
}

function ConvertFrom-JdbcSqlServerUrl {
    param([Parameter(Mandatory)][string]$JdbcUrl)
    # 形如 jdbc:sqlserver://localhost:1433;databaseName=ispan-proj-gymdb;encrypt=false;...
    if ($JdbcUrl -notmatch '^jdbc:sqlserver://([^:;]+)(?::(\d+))?;(.*)$') {
        throw "無法解析的 JDBC URL：$JdbcUrl"
    }
    $serverHost = $matches[1]
    $port = if ($matches[2]) { [int]$matches[2] } else { 1433 }
    $database = $null
    foreach ($pair in $matches[3].Split(';')) {
        if ($pair -match '^databaseName=(.*)$') { $database = $matches[1] }
    }
    if (-not $database) { throw "JDBC URL 裡沒有 databaseName：$JdbcUrl" }
    return [pscustomobject]@{ ServerHost = $serverHost; Port = $port; Database = $database }
}

# ============================================================================
# 正式環境防呆——不論有沒有 -ConfirmTestDatabase 都先做這一段判斷
# ============================================================================

function Assert-NotLikelyProduction {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)]$DbInfo
    )
    $problems = @()

    $localHosts = @("localhost", "127.0.0.1")
    try {
        $baseUri = [Uri]$BaseUrl
        if ($localHosts -notcontains $baseUri.Host) {
            $problems += "BaseUrl 的主機不是 localhost／127.0.0.1（實際：$($baseUri.Host)）"
        }
    } catch {
        $problems += "BaseUrl 不是合法的 URL：$BaseUrl"
    }

    if ($localHosts -notcontains $DbInfo.ServerHost) {
        $problems += "DB_URL 的主機不是 localhost／127.0.0.1（實際：$($DbInfo.ServerHost)）"
    }
    if ($DbInfo.Database -match '(?i)prod|production|正式') {
        $problems += "資料庫名稱看起來像正式環境（實際：$($DbInfo.Database)）"
    }

    if ($problems.Count -gt 0) {
        Write-Host ""
        Write-Host "偵測到疑似正式環境，拒絕執行：" -ForegroundColor Red
        foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
        Write-Host ""
        throw "安全防呆：拒絕對疑似正式環境執行寫入測試"
    }
}

# ============================================================================
# 極簡 SQL Server 存取層（System.Data.SqlClient，Windows PowerShell 內建，免裝模組）
# ============================================================================

$script:SqlConnectionString = $null

function Connect-TestDatabase {
    param(
        [Parameter(Mandatory)]$DbInfo,
        [Parameter(Mandatory)][string]$Username,
        [Parameter(Mandatory)][string]$Password
    )
    $script:SqlConnectionString =
        "Server=$($DbInfo.ServerHost),$($DbInfo.Port);Database=$($DbInfo.Database);" +
        "User Id=$Username;Password=$Password;Encrypt=False;TrustServerCertificate=True;Connection Timeout=10;"

    $conn = New-Object System.Data.SqlClient.SqlConnection($script:SqlConnectionString)
    try {
        $conn.Open()
    } catch {
        throw "無法連線到測試資料庫（伺服器：$($DbInfo.ServerHost):$($DbInfo.Port)，資料庫：$($DbInfo.Database)）。請確認 DB 已啟動且 .env 帳密正確。"
    } finally {
        $conn.Close()
    }
}

function Invoke-SqlNonQuery {
    param([Parameter(Mandatory)][string]$Sql, [hashtable]$Params = @{})
    $conn = New-Object System.Data.SqlClient.SqlConnection($script:SqlConnectionString)
    try {
        $conn.Open()
        $cmd = $conn.CreateCommand()
        $cmd.CommandText = $Sql
        foreach ($key in $Params.Keys) { [void]$cmd.Parameters.AddWithValue("@$key", $Params[$key]) }
        return $cmd.ExecuteNonQuery()
    } finally {
        $conn.Close()
    }
}

function Invoke-SqlScalar {
    param([Parameter(Mandatory)][string]$Sql, [hashtable]$Params = @{})
    $conn = New-Object System.Data.SqlClient.SqlConnection($script:SqlConnectionString)
    try {
        $conn.Open()
        $cmd = $conn.CreateCommand()
        $cmd.CommandText = $Sql
        foreach ($key in $Params.Keys) { [void]$cmd.Parameters.AddWithValue("@$key", $Params[$key]) }
        return $cmd.ExecuteScalar()
    } finally {
        $conn.Close()
    }
}

# ============================================================================
# 極簡 HTTP 呼叫層——回傳統一的 {StatusCode, Body} 物件，不因為 4xx/5xx 就整個腳本中斷
# ============================================================================

function Invoke-ApiCall {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        $Body = $null,
        [string]$BearerToken = $null
    )
    $uri = "$BaseUrl$Path"
    $headers = @{}
    if ($BearerToken) { $headers["Authorization"] = "Bearer $BearerToken" }

    $params = @{
        Method  = $Method
        Uri     = $uri
        Headers = $headers
        ErrorAction = "Stop"
        UseBasicParsing = $true
    }
    if ($Body -ne $null) {
        $params["Body"] = ($Body | ConvertTo-Json -Depth 5)
        $params["ContentType"] = "application/json"
    }

    try {
        $response = Invoke-WebRequest @params
        $parsedBody = $null
        if ($response.Content) {
            try { $parsedBody = $response.Content | ConvertFrom-Json } catch { $parsedBody = $response.Content }
        }
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $parsedBody }
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if ($resp -eq $null) { throw }
        $statusCode = [int]$resp.StatusCode
        $stream = $resp.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $content = $reader.ReadToEnd()
        $parsedBody = $null
        if ($content) {
            try { $parsedBody = $content | ConvertFrom-Json } catch { $parsedBody = $content }
        }
        return [pscustomobject]@{ StatusCode = $statusCode; Body = $parsedBody }
    }
}

# ============================================================================
# 斷言／報告
# ============================================================================

$script:TotalCases = 0
$script:FailedCases = 0

function Test-Case {
    param(
        [Parameter(Mandatory)][string]$Description,
        [Parameter(Mandatory)][bool]$Condition,
        [string]$Detail = $null
    )
    $script:TotalCases++
    if ($Condition) {
        Write-Host "  [PASS] $Description" -ForegroundColor Green
    } else {
        $script:FailedCases++
        $line = "  [FAIL] $Description"
        if ($Detail) { $line += " -- $Detail" }
        Write-Host $line -ForegroundColor Red
    }
}

# ============================================================================
# 測試資料清理——一律在 finally 執行，只刪帶本次 runId 前綴的資料
# ============================================================================

function Remove-TestData {
    param([Parameter(Mandatory)][string]$EmailPrefix)
    if (-not $script:SqlConnectionString) { return }
    Write-Host ""
    Write-Host "清理測試資料（前綴：$EmailPrefix）..." -ForegroundColor Cyan
    try {
        $accountIdFilter = "SELECT account_id FROM account WHERE email LIKE @prefix"
        $prefixParam = @{ prefix = "$EmailPrefix%" }

        Invoke-SqlNonQuery -Sql "DELETE FROM password_history WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM password_reset_token WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM account_role WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM member WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM email_verification WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        $deleted = Invoke-SqlNonQuery -Sql "DELETE FROM account WHERE email LIKE @prefix" -Params $prefixParam
        Write-Host "已清除 $deleted 個測試帳號與其關聯資料" -ForegroundColor Cyan
    } catch {
        Write-Host "清理測試資料時發生錯誤（可能需要手動清理帶 '$EmailPrefix' 前綴的資料列）：$($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# ============================================================================
# 測試帳號建立：走真實的註冊＋Email 驗證流程，密碼雜湊由應用程式自己算出來
# ============================================================================

function New-TestMemberAccount {
    param(
        [Parameter(Mandatory)][string]$Email,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Phone
    )
    $registerBody = @{
        firstName = "E2E"
        lastName  = "Test"
        email     = $Email
        phone     = $Phone
        password  = $Password
    }
    $registerResp = Invoke-ApiCall -Method Post -Path "/api/members/register" -Body $registerBody
    if ($registerResp.StatusCode -ne 201) {
        throw "建立測試帳號失敗：POST /api/members/register 回 $($registerResp.StatusCode)"
    }

    $accountId = [int](Invoke-SqlScalar -Sql "SELECT account_id FROM account WHERE email = @email" -Params @{ email = $Email })
    $verifyToken = Invoke-SqlScalar -Sql @"
SELECT TOP 1 token FROM email_verification
    WHERE account_id = @accountId
    ORDER BY created_at DESC
"@ -Params @{ accountId = $accountId }

    $verifyResp = Invoke-ApiCall -Method Get -Path "/api/accounts/verify-email?token=$verifyToken"
    if ($verifyResp.StatusCode -ne 200) {
        throw "Email 驗證失敗：GET /api/accounts/verify-email 回 $($verifyResp.StatusCode)"
    }

    return $accountId
}

function Invoke-LoginAttempt {
    param([Parameter(Mandatory)][string]$Email, [Parameter(Mandatory)][string]$Password)
    return Invoke-ApiCall -Method Post -Path "/api/auth/login" -Body @{ email = $Email; password = $Password }
}

# ============================================================================
# 主流程
# ============================================================================

Write-Host "=== 登入限流真實 DB E2E 驗證 ===" -ForegroundColor Cyan
Write-Host ""

$envVars = Read-DotEnv -Path $EnvFile
$dbUrl = if ($envVars.ContainsKey("DB_URL")) { $envVars["DB_URL"] } else { "jdbc:sqlserver://localhost:1433;databaseName=ispan-proj-gymdb;encrypt=false;trustServerCertificate=true" }
$dbUsername = if ($envVars.ContainsKey("DB_USERNAME")) { $envVars["DB_USERNAME"] } else { "sa" }
if (-not $envVars.ContainsKey("DB_PASSWORD")) {
    throw ".env 裡沒有 DB_PASSWORD，無法連線"
}
$dbPassword = $envVars["DB_PASSWORD"]
$dbInfo = ConvertFrom-JdbcSqlServerUrl -JdbcUrl $dbUrl

Write-Host "將連線的目標（帳密已遮蔽）："
Write-Host "  API base URL : $BaseUrl"
Write-Host "  DB 伺服器    : $($dbInfo.ServerHost):$($dbInfo.Port)"
Write-Host "  DB 資料庫    : $($dbInfo.Database)"
Write-Host "  DB 使用者    : $($dbUsername.Substring(0, [Math]::Min(2, $dbUsername.Length)))***"
Write-Host ""

Assert-NotLikelyProduction -BaseUrl $BaseUrl -DbInfo $dbInfo

if (-not $ConfirmTestDatabase) {
    Write-Host "未帶 -ConfirmTestDatabase，僅顯示上述連線資訊，不執行任何動作。" -ForegroundColor Yellow
    Write-Host "確認以上目標正確後，加上 -ConfirmTestDatabase 參數重新執行。" -ForegroundColor Yellow
    exit 0
}

Connect-TestDatabase -DbInfo $dbInfo -Username $dbUsername -Password $dbPassword
Write-Host "資料庫連線確認成功。" -ForegroundColor Green
Write-Host ""

$runId = New-RunId
$emailPrefix = "login-rl-e2e-$runId"
Write-Host "本次執行的測試資料前綴：$emailPrefix" -ForegroundColor Cyan
Write-Host ""

try {
    $password = "E2eLogin@01"

    # ------------------------------------------------------------------
    # 第一段：建立測試帳號（baseline／A／B／D）
    # ------------------------------------------------------------------
    Write-Host "--- 建立測試帳號 ---" -ForegroundColor Cyan

    $emailBaseline = "$emailPrefix-baseline@test.local"
    $emailA = "$emailPrefix-a@test.local"
    $emailB = "$emailPrefix-b@test.local"
    $emailD = "$emailPrefix-d@test.local"

    $idBaseline = New-TestMemberAccount -Email $emailBaseline -Password $password -Phone "0900000101"
    $idA = New-TestMemberAccount -Email $emailA -Password $password -Phone "0900000102"
    $idB = New-TestMemberAccount -Email $emailB -Password $password -Phone "0900000103"
    $idD = New-TestMemberAccount -Email $emailD -Password $password -Phone "0900000104"
    Test-Case "四個測試帳號建立並通過 Email 驗證" ($idBaseline -gt 0 -and $idA -gt 0 -and $idB -gt 0 -and $idD -gt 0)

    # ------------------------------------------------------------------
    # 第二段：正常登入不受限流影響
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 正常登入 ---" -ForegroundColor Cyan

    $baselineResp = Invoke-LoginAttempt -Email $emailBaseline -Password $password
    Test-Case "帳密正確時正常登入成功（200）" ($baselineResp.StatusCode -eq 200)

    # ------------------------------------------------------------------
    # 第三段（帳號 A）：連續 5 次錯誤密碼後第 6 次觸發 429，且正確密碼也救不回來
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- Email 層限流：連續錯誤觸發 429 ---" -ForegroundColor Cyan

    $allWrongWere401 = $true
    for ($i = 0; $i -lt 5; $i++) {
        $resp = Invoke-LoginAttempt -Email $emailA -Password "wrong-password-$i"
        if ($resp.StatusCode -ne 401) { $allWrongWere401 = $false }
    }
    Test-Case "帳號 A 連續 5 次錯誤密碼皆回 401（尚未觸發限流）" $allWrongWere401

    $sixthResp = Invoke-LoginAttempt -Email $emailA -Password "wrong-password-6"
    Test-Case "帳號 A 第 6 次錯誤密碼回 429 LOGIN_RATE_LIMITED" `
        ($sixthResp.StatusCode -eq 429 -and $sixthResp.Body.code -eq "LOGIN_RATE_LIMITED") `
        "狀態碼=$($sixthResp.StatusCode) code=$($sixthResp.Body.code)"

    $correctWhileBlockedResp = Invoke-LoginAttempt -Email $emailA -Password $password
    Test-Case "帳號 A 觸發限流後改用正確密碼仍回 429（限流檢查先於密碼比對）" `
        ($correctWhileBlockedResp.StatusCode -eq 429)

    # 到這裡本次執行已經對同一支來源 IP 發出 8 次登入請求（baseline 1 次＋帳號 A 7 次），
    # IP 層門檻是每分鐘 10 次（與忘記密碼一致，見 AuthController）——下一段帳號 B 還要
    # 再發 7 次，若不等 IP 層 1 分鐘的滑動視窗先清空，會在帳號 B 驗證「清空計數」的
    # 過程中被 IP 層提前擋下，變成誤判成 email 層沒有清空。
    Write-Host ""
    Write-Host "等待 65 秒，讓 IP 層 1 分鐘滑動視窗清空，避免影響下一段 email 層驗證..." -ForegroundColor DarkGray
    Start-Sleep -Seconds 65

    # ------------------------------------------------------------------
    # 第四段（帳號 B）：登入成功會清空該 email 的失敗計數
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- Email 層限流：成功登入清空計數 ---" -ForegroundColor Cyan

    for ($i = 0; $i -lt 3; $i++) {
        Invoke-LoginAttempt -Email $emailB -Password "wrong-password-$i" | Out-Null
    }
    $clearingLoginResp = Invoke-LoginAttempt -Email $emailB -Password $password
    Test-Case "帳號 B 前 3 次錯誤後、第 4 次用正確密碼登入成功" ($clearingLoginResp.StatusCode -eq 200)

    # 若清空沒有真的發生，累計失敗次數會是 3+3=6，早該在下面第 6 次觸發 429；
    # 這裡預期全部仍是 401，證明計數確實從 0 重新算
    $postClearAllWere401 = $true
    for ($i = 0; $i -lt 3; $i++) {
        $resp = Invoke-LoginAttempt -Email $emailB -Password "wrong-password-again-$i"
        if ($resp.StatusCode -ne 401) { $postClearAllWere401 = $false }
    }
    Test-Case "帳號 B 清空計數後再錯 3 次仍是 401（不是 429，證明計數確實重置）" $postClearAllWere401

    # ------------------------------------------------------------------
    # 第五段（帳號 D）：不同帳號的失敗計數彼此獨立
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- Email 層限流：不同帳號互不影響 ---" -ForegroundColor Cyan

    $dResp = Invoke-LoginAttempt -Email $emailD -Password "wrong-password"
    Test-Case "帳號 A 已觸發過限流、帳號 B 曾錯誤但已被清空，帳號 D 第一次錯誤密碼仍是 401（不受牽連）" `
        ($dResp.StatusCode -eq 401 -and $dResp.Body.code -eq "UNAUTHENTICATED") `
        "狀態碼=$($dResp.StatusCode) code=$($dResp.Body.code)"

    # 到這裡本次執行已對同一支來源 IP 累計發出 8 次登入請求（帳號 B 7 次＋帳號 D 1 次，
    # 前面帳號 A／baseline 那 8 次已隨 65 秒等待清空）——下面要刻意把 IP 層灌爆到超過
    # 每分鐘 10 次的門檻，必須先讓視窗清空，才能乾淨地驗證「前 10 次通過、第 11 次擋下」，
    # 而不是被前面殘留的次數影響到實際觸發的那一次是第幾次。
    Write-Host ""
    Write-Host "等待 65 秒，讓 IP 層 1 分鐘滑動視窗清空，準備驗證 IP 層本身的門檻..." -ForegroundColor DarkGray
    Start-Sleep -Seconds 65

    # ------------------------------------------------------------------
    # 第六段：IP 層限流——同一支 IP 即使每次用不同（不存在的）Email，累計次數
    # 仍會觸發 429，證明 IP 層與 email 層是各自獨立的兩道防線
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- IP 層限流：同 IP 大量嘗試即使各自 Email 不同仍觸發 429 ---" -ForegroundColor Cyan

    # 這 10 個 Email 純粹用來讓 AuthController 走到「查無帳號」那條路徑，測的是 IP 層，
    # 不需要真的註冊——AuthController.LOGIN_IP_MINUTE_MAX = 10，10 次應該都還過得去
    $allTenWere401 = $true
    for ($i = 0; $i -lt 10; $i++) {
        $resp = Invoke-LoginAttempt -Email "$emailPrefix-ip-$i@test.local" -Password "wrong-password"
        if ($resp.StatusCode -ne 401) { $allTenWere401 = $false }
    }
    Test-Case "同一 IP 前 10 次（各自不同 Email）皆回 401（IP 層尚未超額）" $allTenWere401

    $eleventhResp = Invoke-LoginAttempt -Email "$emailPrefix-ip-final@test.local" -Password "wrong-password"
    Test-Case "同一 IP 第 11 次（不同 Email）觸發 429 LOGIN_RATE_LIMITED（IP 層獨立於 email 層）" `
        ($eleventhResp.StatusCode -eq 429 -and $eleventhResp.Body.code -eq "LOGIN_RATE_LIMITED") `
        "狀態碼=$($eleventhResp.StatusCode) code=$($eleventhResp.Body.code)"

    Write-Host ""
    Write-Host "=== 驗證完成：$($script:TotalCases) 個案例，$($script:FailedCases) 個失敗 ===" -ForegroundColor $(if ($script:FailedCases -eq 0) { "Green" } else { "Red" })
}
finally {
    Remove-TestData -EmailPrefix $emailPrefix
}

if ($script:FailedCases -gt 0) {
    exit 1
} else {
    exit 0
}
