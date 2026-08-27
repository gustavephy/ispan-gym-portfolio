<#
.SYNOPSIS
    Email 即時可用性檢查（POST /api/accounts/check-email-availability）的真實 SQL Server
    opt-in E2E 驗證腳本。

.DESCRIPTION
    比照 test-login-rate-limit-e2e.ps1／test-password-management-e2e.ps1 的既有慣例：
    預設（不帶 -ConfirmTestDatabase）只讀取 .env、印出將連線的 DB 資訊，然後直接結束——
    不建立任何連線、不呼叫任何 API、不寫入任何資料。要真的執行完整驗證，必須明確加上
    -ConfirmTestDatabase。

    所有測試帳號的 Email 都帶同一個本次執行專屬的隨機字串前綴
    （email-check-e2e-<runId>-...），腳本結束前（不論成功或失敗）一律在 finally 區塊
    清掉自己建立的資料。

    驗證的重點是：「可用」不等於「查無這個帳號」——PENDING 未驗證、軟刪除的帳號都要
    回 available=true（因為送出 POST /api/members/register 實際上會成功，只是觸發
    重寄驗證信／重新啟用信，不會報錯），只有帳號已被其他身分占用、已是完整 ACTIVE
    會員、或已被停權時才回 false（這幾種情況 register() 真的會擋下來）。

    IP 限流門檻是每分鐘 10 次、每小時 30 次（與登入的 IP 層一致，見
    AccountVerificationController）。前面幾段情境測試加起來遠低於 10 次，不會誤觸發；
    最後一段刻意灌到超過門檻，中間插入 65 秒等待讓滑動視窗先清空，才能乾淨驗證
    「前 10 次通過、第 11 次擋下」。

.PARAMETER BaseUrl
    後端 API 的 base URL。刻意要求是 localhost／127.0.0.1，避免不小心打到別的環境。

.PARAMETER ConfirmTestDatabase
    唯一能讓腳本真的執行寫入操作的開關。沒有這個參數，腳本只會做唯讀的連線資訊預覽。

.PARAMETER EnvFile
    .env 檔案路徑，預設是這支腳本所在目錄的上一層（專案根目錄）。

.EXAMPLE
    # 只看看會連到哪個 DB，不執行任何動作
    .\test-email-availability-check-e2e.ps1

.EXAMPLE
    # 確認過上面印出的 DB 資訊之後，真的執行
    .\test-email-availability-check-e2e.ps1 -ConfirmTestDatabase
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
# 正式環境防呆
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
# 極簡 SQL Server 存取層
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
# 極簡 HTTP 呼叫層
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

function Invoke-CheckEmailAvailability {
    param([Parameter(Mandatory)][string]$Email)
    return Invoke-ApiCall -Method Post -Path "/api/accounts/check-email-availability" -Body @{ email = $Email }
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
# 測試資料清理
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
# 測試帳號建立：走真實的註冊 API，密碼雜湊由應用程式自己算出來
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
    return [int](Invoke-SqlScalar -Sql "SELECT account_id FROM account WHERE email = @email" -Params @{ email = $Email })
}

function Confirm-TestMemberEmail {
    param([Parameter(Mandatory)][int]$AccountId)
    $verifyToken = Invoke-SqlScalar -Sql @"
SELECT TOP 1 token FROM email_verification
    WHERE account_id = @accountId
    ORDER BY created_at DESC
"@ -Params @{ accountId = $AccountId }
    $verifyResp = Invoke-ApiCall -Method Get -Path "/api/accounts/verify-email?token=$verifyToken"
    if ($verifyResp.StatusCode -ne 200) {
        throw "Email 驗證失敗：GET /api/accounts/verify-email 回 $($verifyResp.StatusCode)"
    }
}

# ============================================================================
# 主流程
# ============================================================================

Write-Host "=== Email 即時可用性檢查真實 DB E2E 驗證 ===" -ForegroundColor Cyan
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
$emailPrefix = "email-check-e2e-$runId"
Write-Host "本次執行的測試資料前綴：$emailPrefix" -ForegroundColor Cyan
Write-Host ""

try {
    $password = "E2eCheck@01"

    # ------------------------------------------------------------------
    # 第一段：全新 Email 視為可用
    # ------------------------------------------------------------------
    Write-Host "--- 全新 Email ---" -ForegroundColor Cyan

    $freshEmail = "$emailPrefix-fresh@test.local"
    $freshResp = Invoke-CheckEmailAvailability -Email $freshEmail
    Test-Case "全新 Email 回 200 且 available=true" `
        ($freshResp.StatusCode -eq 200 -and $freshResp.Body.available -eq $true) `
        "狀態碼=$($freshResp.StatusCode) available=$($freshResp.Body.available)"

    # ------------------------------------------------------------------
    # 第二段：ACTIVE 會員視為不可用（含大小寫正規化）
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- ACTIVE 會員（含大小寫正規化） ---" -ForegroundColor Cyan

    $activeEmail = "$emailPrefix-active@test.local"
    $activeAccountId = New-TestMemberAccount -Email $activeEmail -Password $password -Phone "0900000201"
    Confirm-TestMemberEmail -AccountId $activeAccountId

    $activeResp = Invoke-CheckEmailAvailability -Email $activeEmail
    Test-Case "ACTIVE 會員的 Email 回 available=false" `
        ($activeResp.StatusCode -eq 200 -and $activeResp.Body.available -eq $false) `
        "available=$($activeResp.Body.available)"

    $upperCaseResp = Invoke-CheckEmailAvailability -Email $activeEmail.ToUpperInvariant()
    Test-Case "同一 Email 改大寫查詢，結果一致回 available=false（大小寫正規化）" `
        ($upperCaseResp.StatusCode -eq 200 -and $upperCaseResp.Body.available -eq $false) `
        "available=$($upperCaseResp.Body.available)"

    # ------------------------------------------------------------------
    # 第三段：PENDING 未驗證帳號仍視為可用（送出表單只會重寄驗證信，不會報錯）
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- PENDING 未驗證帳號 ---" -ForegroundColor Cyan

    $pendingEmail = "$emailPrefix-pending@test.local"
    $pendingAccountId = New-TestMemberAccount -Email $pendingEmail -Password $password -Phone "0900000202"
    # 刻意不驗證，維持 PENDING 狀態

    $pendingResp = Invoke-CheckEmailAvailability -Email $pendingEmail
    Test-Case "PENDING 未驗證帳號的 Email 仍回 available=true（不等於「查無帳號」）" `
        ($pendingResp.StatusCode -eq 200 -and $pendingResp.Body.available -eq $true) `
        "available=$($pendingResp.Body.available)"

    # ------------------------------------------------------------------
    # 第四段：軟刪除帳號仍視為可用（送出表單只會觸發重新啟用信，不會報錯）
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 軟刪除帳號 ---" -ForegroundColor Cyan

    $deletedEmail = "$emailPrefix-deleted@test.local"
    $deletedAccountId = New-TestMemberAccount -Email $deletedEmail -Password $password -Phone "0900000203"
    Invoke-SqlNonQuery -Sql "UPDATE account SET deleted_at = SYSDATETIME() WHERE account_id = @id" `
        -Params @{ id = $deletedAccountId } | Out-Null
    Invoke-SqlNonQuery -Sql "UPDATE member SET deleted_at = SYSDATETIME() WHERE account_id = @id" `
        -Params @{ id = $deletedAccountId } | Out-Null

    $deletedResp = Invoke-CheckEmailAvailability -Email $deletedEmail
    Test-Case "軟刪除帳號的 Email 仍回 available=true（不等於「查無帳號」）" `
        ($deletedResp.StatusCode -eq 200 -and $deletedResp.Body.available -eq $true) `
        "available=$($deletedResp.Body.available)"

    # ------------------------------------------------------------------
    # 第五段：格式錯誤回 400
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- Email 格式錯誤 ---" -ForegroundColor Cyan

    $invalidResp = Invoke-CheckEmailAvailability -Email "not-an-email"
    Test-Case "Email 格式錯誤回 400" ($invalidResp.StatusCode -eq 400)

    # 到這裡本次執行對 check-email-availability 累計發出約 6 次請求（IP 層門檻是每分鐘
    # 10 次），下一段要刻意灌到超過門檻，必須先讓視窗清空，才能乾淨驗證「前 10 次通過、
    # 第 11 次擋下」，不被前面殘留的次數影響到實際觸發的那一次是第幾次。
    Write-Host ""
    Write-Host "等待 65 秒，讓 IP 層 1 分鐘滑動視窗清空，準備驗證 IP 層限流..." -ForegroundColor DarkGray
    Start-Sleep -Seconds 65

    # ------------------------------------------------------------------
    # 第六段：IP 層限流——同一支 IP 即使每次查不同 Email，累計次數仍會觸發 429
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- IP 層限流 ---" -ForegroundColor Cyan

    $allTenWere200 = $true
    for ($i = 0; $i -lt 10; $i++) {
        $resp = Invoke-CheckEmailAvailability -Email "$emailPrefix-ip-$i@test.local"
        if ($resp.StatusCode -ne 200) { $allTenWere200 = $false }
    }
    Test-Case "同一 IP 前 10 次（各自不同 Email）皆回 200（IP 層尚未超額）" $allTenWere200

    $eleventhResp = Invoke-CheckEmailAvailability -Email "$emailPrefix-ip-final@test.local"
    Test-Case "同一 IP 第 11 次觸發 429 EMAIL_CHECK_RATE_LIMITED" `
        ($eleventhResp.StatusCode -eq 429 -and $eleventhResp.Body.code -eq "EMAIL_CHECK_RATE_LIMITED") `
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
