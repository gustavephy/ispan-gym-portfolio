<#
.SYNOPSIS
    密碼管理功能的真實 SQL Server opt-in E2E 驗證腳本。

.DESCRIPTION
    這支腳本刻意不是 mvnw test 的一部分（見 memory/password_management_phase_scope.md 的
    決策：這個專案沒有自動化的真實 DB 測試機制，SchemaFixupRunner 直接查 sys.* 系統目錄
    視圖，就算接上 H2 也跑不動，schema 遷移這類東西本質上只能對真正的 SQL Server 驗證）。

    預設（不帶 -ConfirmTestDatabase）只會讀取 .env、解析出將要連線的 DB 資訊、印出來給你
    確認，然後直接結束——不建立任何連線、不呼叫任何 API、不寫入任何資料。要真的執行完整
    驗證，必須明確加上 -ConfirmTestDatabase。

    所有測試資料的 Email 都帶同一個本次執行專屬的隨機字串前綴（password-e2e-<runId>-...），
    腳本結束前（不論成功或失敗）一律在 finally 區塊清掉自己建立的資料，絕不觸碰任何其他
    帳號——不會、也不需要動到 wendy-test@local.test、管理員種子帳號或其他既有展示資料。

    不會執行任何 DROP／ALTER／TRUNCATE，也不會刻意把 schema 改回舊版再重新遷移一次
    （模擬「舊資料庫升級」需要一個可丟棄的測試 DB，不是這支腳本的範圍——見腳本內對應章節
    的說明）。只讀取／插入／刪除「自己建立、帶專屬前綴」的那批資料列。

.PARAMETER BaseUrl
    後端 API 的 base URL。刻意要求是 localhost／127.0.0.1，避免不小心打到別的環境。

.PARAMETER ConfirmTestDatabase
    唯一能讓腳本真的執行寫入操作的開關。沒有這個參數，腳本只會做唯讀的連線資訊預覽。

.PARAMETER EnvFile
    .env 檔案路徑，預設是這支腳本所在目錄的上一層（專案根目錄）。

.EXAMPLE
    # 只看看會連到哪個 DB，不執行任何動作
    .\test-password-management-e2e.ps1

.EXAMPLE
    # 確認過上面印出的 DB 資訊之後，真的執行
    .\test-password-management-e2e.ps1 -ConfirmTestDatabase
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$ConfirmTestDatabase,
    [string]$EnvFile = ""
)

$ErrorActionPreference = "Stop"

# 主控台輸出強制用 UTF-8，避免中文訊息在非預設 codepage 的終端機（例如從 Bash 包呼叫
# powershell.exe 時）被錯誤解碼成亂碼——這只影響顯示，不影響腳本邏輯本身。
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # 某些非互動式主控台不允許設定 OutputEncoding，失敗就算了，純粹是顯示美觀問題
}

# $PSScriptRoot 在 param() 區塊的預設值運算式裡不保證已經設好（部分 PowerShell 版本的已知
# 限制），改在腳本主體內用 $PSCommandPath 算出來，那個變數在這個時間點一定是有值的。
if ([string]::IsNullOrEmpty($EnvFile)) {
    $EnvFile = Join-Path (Split-Path -Parent $PSCommandPath) "..\.env"
}

# ============================================================================
# 共用工具：Email 正規化用的雜湊、Token 產生、SHA-256——都不外洩明文機密到輸出
# ============================================================================

function Get-Sha256Hex {
    param([Parameter(Mandatory)][string]$Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        $hash = $sha.ComputeHash($bytes)
        return -join ($hash | ForEach-Object { $_.ToString("x2") })
    } finally {
        $sha.Dispose()
    }
}

# 比照後端 PasswordService.generateRawToken()：32 bytes、Base64 URL-safe、無 padding
function New-RawResetToken {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    $b64 = [Convert]::ToBase64String($bytes)
    return $b64.Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

function New-RunId {
    return ([Guid]::NewGuid().ToString("N")).Substring(0, 10)
}

# ============================================================================
# .env 解析——只取需要的三個 key，絕不把值印到主控台或寫進任何 log
# ============================================================================

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
            # 去掉可能包住整個值的引號
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

# 把主程式的 spring.datasource.url 預設值（JDBC 格式）解析成連線用得到的片段。
# 只解析 host/port/databaseName，不解析、也不需要密碼——密碼另外從 DB_PASSWORD 拿。
function ConvertFrom-JdbcSqlServerUrl {
    param([Parameter(Mandatory)][string]$JdbcUrl)
    # 例：jdbc:sqlserver://localhost:1433;databaseName=ispan-proj-gymdb;encrypt=false;trustServerCertificate=true
    if ($JdbcUrl -notmatch '^jdbc:sqlserver://([^:;]+)(?::(\d+))?;(.*)$') {
        throw "無法解析 DB_URL 格式（非預期的 jdbc:sqlserver:// 格式）"
    }
    $serverHost = $matches[1]
    $port = if ($matches[2]) { $matches[2] } else { "1433" }
    $rest = $matches[3]
    $databaseName = $null
    foreach ($pair in $rest -split ';') {
        if ($pair -match '^databaseName=(.*)$') { $databaseName = $matches[1] }
    }
    if (-not $databaseName) {
        throw "DB_URL 裡沒有 databaseName，無法安全地判斷要連線的資料庫"
    }
    return [pscustomobject]@{
        ServerHost = $serverHost
        Port       = $port
        Database   = $databaseName
    }
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

    # 立刻測一次連線，連不上就直接失敗結束，不要拖到第一個測試案例才發現
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

function Invoke-SqlInsertGetIdentity {
    param([Parameter(Mandatory)][string]$Sql, [hashtable]$Params = @{})
    return [int](Invoke-SqlScalar -Sql "$Sql; SELECT SCOPE_IDENTITY();" -Params $Params)
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
        # Windows PowerShell 5.1 的 Invoke-WebRequest 預設會嘗試用 IE 的剖析引擎解析回應，
        # 在沒有完成過 IE 首次執行設定的機器上會直接丟 NullReferenceException（跟目標
        # 伺服器或回應內容完全無關）——一律加 -UseBasicParsing 繞開那個引擎。
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
# 測試帳號建立：走真實的註冊＋Email 驗證流程，而不是手刻 BCrypt hash 直接塞資料庫——
# 這樣密碼雜湊是應用程式自己算出來的，跟真實使用者建立帳號的路徑完全一致。
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

function Get-AuthToken {
    param([Parameter(Mandatory)][string]$Email, [Parameter(Mandatory)][string]$Password)
    $resp = Invoke-ApiCall -Method Post -Path "/api/auth/login" -Body @{ email = $Email; password = $Password }
    if ($resp.StatusCode -ne 200) {
        throw "登入失敗（$Email）：回 $($resp.StatusCode)"
    }
    return $resp.Body.accessToken
}

function Test-ProfileReachable {
    param([Parameter(Mandatory)][string]$Token)
    $resp = Invoke-ApiCall -Method Get -Path "/api/member/profile" -BearerToken $Token
    return $resp.StatusCode -eq 200
}

# ============================================================================
# 主流程
# ============================================================================

Write-Host "=== 密碼管理真實 DB E2E 驗證 ===" -ForegroundColor Cyan
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
$emailPrefix = "password-e2e-$runId"
Write-Host "本次執行的測試資料前綴：$emailPrefix" -ForegroundColor Cyan
Write-Host ""

try {
    # ------------------------------------------------------------------
    # 第一段：schema 現況檢查（純唯讀，不寫入）
    # ------------------------------------------------------------------
    Write-Host "--- Schema 現況檢查 ---" -ForegroundColor Cyan

    # sys.columns.is_nullable 是 SQL Server 的 bit 型別，ADO.NET 讀回來是 .NET bool
    # （不是 INFORMATION_SCHEMA.COLUMNS 那種 "YES"/"NO" 字串），要跟 $false 比較。
    $cvNullable = Invoke-SqlScalar -Sql @"
SELECT is_nullable FROM sys.columns WHERE object_id = OBJECT_ID('account') AND name = 'credentials_version'
"@
    Test-Case "account.credentials_version 欄位存在且為 NOT NULL" ($cvNullable -eq $false) "is_nullable=$cvNullable"

    $cvNullCount = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM account WHERE credentials_version IS NULL"
    Test-Case "既有帳號沒有 credentials_version 為 NULL 的資料列" ([int]$cvNullCount -eq 0) "NULL 列數=$cvNullCount"

    $cvNegativeCount = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM account WHERE credentials_version < 0"
    Test-Case "既有帳號的 credentials_version 都是合法非負整數" ([int]$cvNegativeCount -eq 0) "負數列數=$cvNegativeCount"

    $tokenHashNullable = Invoke-SqlScalar -Sql @"
SELECT is_nullable FROM sys.columns WHERE object_id = OBJECT_ID('password_reset_token') AND name = 'token_hash'
"@
    Test-Case "password_reset_token.token_hash 欄位存在且為 NOT NULL" ($tokenHashNullable -eq $false) "is_nullable=$tokenHashNullable"

    $uniqueIndexCount = Invoke-SqlScalar -Sql @"
SELECT COUNT(*) FROM sys.indexes WHERE name = 'UX_password_reset_token_token_hash' AND object_id = OBJECT_ID('password_reset_token')
"@
    Test-Case "token_hash 有唯一索引 UX_password_reset_token_token_hash" ([int]$uniqueIndexCount -eq 1)

    $oldTokenColumnCount = Invoke-SqlScalar -Sql @"
SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID('password_reset_token') AND name = 'token'
"@
    Test-Case "舊版明文 token 欄位已不存在" ([int]$oldTokenColumnCount -eq 0)

    Write-Host ""
    Write-Host "（註：SchemaFixupRunner 重啟後冪等、不清除仍有效的新格式 Token，" -ForegroundColor DarkGray
    Write-Host "  這件事需要實際重啟應用程式才能驗證，已在開發過程手動驗證確認，" -ForegroundColor DarkGray
    Write-Host "  記錄於 memory/password_management_phase_scope.md，不重複列入本腳本自動化範圍）" -ForegroundColor DarkGray
    Write-Host ""

    # ------------------------------------------------------------------
    # 第二段：建立兩個測試帳號
    # ------------------------------------------------------------------
    Write-Host "--- 建立測試帳號 ---" -ForegroundColor Cyan

    $emailA = "$emailPrefix-a@test.local"
    $emailB = "$emailPrefix-b@test.local"
    $initialPassword = "E2eInit@01"

    $accountIdA = New-TestMemberAccount -Email $emailA -Password $initialPassword -Phone "0900000001"
    $accountIdB = New-TestMemberAccount -Email $emailB -Password $initialPassword -Phone "0900000002"
    Test-Case "測試帳號 A／B 建立並通過 Email 驗證" ($accountIdA -gt 0 -and $accountIdB -gt 0)

    # ------------------------------------------------------------------
    # 第三段（帳號 A）：修改密碼後舊 JWT 401、新 JWT 可用
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 修改密碼：JWT 失效／新 JWT 可用 ---" -ForegroundColor Cyan

    $tokenA0 = Get-AuthToken -Email $emailA -Password $initialPassword
    Test-Case "帳號 A 初始 JWT 可存取 /api/member/profile" (Test-ProfileReachable -Token $tokenA0)

    $changeResp = Invoke-ApiCall -Method Post -Path "/api/account/me/change-password" -BearerToken $tokenA0 -Body @{
        currentPassword = $initialPassword; newPassword = "E2eStep@02"; confirmPassword = "E2eStep@02"
    }
    Test-Case "修改密碼成功回 200" ($changeResp.StatusCode -eq 200)
    $tokenA1 = $changeResp.Body.accessToken
    Test-Case "修改密碼回應內含新 JWT" (-not [string]::IsNullOrEmpty($tokenA1))

    Test-Case "修改密碼後舊 JWT 立即失效（401）" (-not (Test-ProfileReachable -Token $tokenA0))
    Test-Case "修改密碼後新 JWT 可正常使用" (Test-ProfileReachable -Token $tokenA1)

    # ------------------------------------------------------------------
    # 第四段（帳號 A）：失敗路徑不會部分更新密碼／歷史／版本
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 失敗路徑不會部分更新資料 ---" -ForegroundColor Cyan

    $beforeHash = Invoke-SqlScalar -Sql "SELECT password_hash FROM account WHERE account_id = @id" -Params @{ id = $accountIdA }
    $beforeVersion = Invoke-SqlScalar -Sql "SELECT credentials_version FROM account WHERE account_id = @id" -Params @{ id = $accountIdA }
    $beforeHistoryCount = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM password_history WHERE account_id = @id" -Params @{ id = $accountIdA }

    $wrongResp = Invoke-ApiCall -Method Post -Path "/api/account/me/change-password" -BearerToken $tokenA1 -Body @{
        currentPassword = "TotallyWrong@99"; newPassword = "E2eStep@03"; confirmPassword = "E2eStep@03"
    }
    Test-Case "目前密碼錯誤時回 400" ($wrongResp.StatusCode -eq 400)

    $afterHash = Invoke-SqlScalar -Sql "SELECT password_hash FROM account WHERE account_id = @id" -Params @{ id = $accountIdA }
    $afterVersion = Invoke-SqlScalar -Sql "SELECT credentials_version FROM account WHERE account_id = @id" -Params @{ id = $accountIdA }
    $afterHistoryCount = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM password_history WHERE account_id = @id" -Params @{ id = $accountIdA }

    Test-Case "失敗嘗試後 password_hash 未變更" ($beforeHash -eq $afterHash)
    Test-Case "失敗嘗試後 credentials_version 未變更" ([int]$beforeVersion -eq [int]$afterVersion)
    Test-Case "失敗嘗試後 PasswordHistory 筆數未變更" ([int]$beforeHistoryCount -eq [int]$afterHistoryCount)

    # ------------------------------------------------------------------
    # 第五段（帳號 A）：PasswordHistory 最多 4 筆、近 5 組不可重用、超出窗口可重用
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- PasswordHistory：裁切上限與重用窗口 ---" -ForegroundColor Cyan

    $passwords = @("E2eStep@03", "E2eStep@04", "E2eStep@05", "E2eStep@06")
    $currentToken = $tokenA1
    $currentPassword = "E2eStep@02"
    foreach ($nextPassword in $passwords) {
        $resp = Invoke-ApiCall -Method Post -Path "/api/account/me/change-password" -BearerToken $currentToken -Body @{
            currentPassword = $currentPassword; newPassword = $nextPassword; confirmPassword = $nextPassword
        }
        Test-Case "連續修改密碼至 $nextPassword 成功" ($resp.StatusCode -eq 200)
        $currentToken = $resp.Body.accessToken
        $currentPassword = $nextPassword
    }
    # 此時歷史：目前=E2eStep@06，history 4 筆＝[05,04,03,02]，E2eInit@01 已經被裁掉

    $historyCount = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM password_history WHERE account_id = @id" -Params @{ id = $accountIdA }
    Test-Case "PasswordHistory 裁切後最多 4 筆" ([int]$historyCount -eq 4) "實際=$historyCount"

    $reuseRecentResp = Invoke-ApiCall -Method Post -Path "/api/account/me/change-password" -BearerToken $currentToken -Body @{
        currentPassword = $currentPassword; newPassword = "E2eStep@05"; confirmPassword = "E2eStep@05"
    }
    Test-Case "重用近期歷史密碼（05）被擋下（409）" ($reuseRecentResp.StatusCode -eq 409)

    $reuseExpiredResp = Invoke-ApiCall -Method Post -Path "/api/account/me/change-password" -BearerToken $currentToken -Body @{
        currentPassword = $currentPassword; newPassword = $initialPassword; confirmPassword = $initialPassword
    }
    Test-Case "已超出重用窗口的最初密碼可以重新使用" ($reuseExpiredResp.StatusCode -eq 200)

    # ------------------------------------------------------------------
    # 第六段（帳號 B）：忘記密碼／重設密碼後全部舊 JWT 失效
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 重設密碼：所有舊 JWT 失效 ---" -ForegroundColor Cyan

    $tokenB0 = Get-AuthToken -Email $emailB -Password $initialPassword
    Test-Case "帳號 B 初始 JWT 可存取 /api/member/profile" (Test-ProfileReachable -Token $tokenB0)

    # 直接插入已知 Reset Token 的雜湊值——不透過 API 取得明文 Token（產品 API 本來就不提供），
    # 只在測試資料庫直接寫入我們自己算好的 SHA-256 雜湊，這是使用者明確同意的做法。
    $rawTokenB = New-RawResetToken
    $tokenHashB = Get-Sha256Hex -Text $rawTokenB
    Invoke-SqlNonQuery -Sql @"
INSERT INTO password_reset_token (account_id, token_hash, expires_at, created_at)
VALUES (@accountId, @tokenHash, DATEADD(MINUTE, 15, SYSDATETIME()), SYSDATETIME())
"@ -Params @{ accountId = $accountIdB; tokenHash = $tokenHashB } | Out-Null

    $resetResp = Invoke-ApiCall -Method Post -Path "/api/auth/reset-password" -Body @{
        token = $rawTokenB; newPassword = "E2eReset@02"; confirmPassword = "E2eReset@02"
    }
    Test-Case "憑 Token 重設密碼成功回 204" ($resetResp.StatusCode -eq 204)
    Test-Case "重設密碼後舊 JWT 立即失效（401）" (-not (Test-ProfileReachable -Token $tokenB0))

    $tokenB1 = Get-AuthToken -Email $emailB -Password "E2eReset@02"
    Test-Case "以新密碼可以重新登入" (-not [string]::IsNullOrEmpty($tokenB1))

    # ------------------------------------------------------------------
    # 第七段（帳號 B）：同一個 Token 成功使用後不可重用
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- Token 單次使用 ---" -ForegroundColor Cyan

    $reuseTokenResp = Invoke-ApiCall -Method Post -Path "/api/auth/reset-password" -Body @{
        token = $rawTokenB; newPassword = "E2eReset@03"; confirmPassword = "E2eReset@03"
    }
    Test-Case "已使用過的 Token 無法再次重設密碼（400）" ($reuseTokenResp.StatusCode -eq 400)

    # ------------------------------------------------------------------
    # 第八段（帳號 B）：新申請的 Token 讓同帳號舊 Token 作廢
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 新 Reset Token 讓同帳號舊 Token 作廢 ---" -ForegroundColor Cyan

    $forgot1 = Invoke-ApiCall -Method Post -Path "/api/auth/forgot-password" -Body @{ email = $emailB }
    Test-Case "第一次忘記密碼申請回 202" ($forgot1.StatusCode -eq 202)
    Start-Sleep -Milliseconds 200

    $firstTokenId = Invoke-SqlScalar -Sql @"
SELECT TOP 1 id FROM password_reset_token
    WHERE account_id = @id AND used_at IS NULL AND invalidated_at IS NULL
    ORDER BY created_at DESC
"@ -Params @{ id = $accountIdB }
    Test-Case "第一次申請確實建立了一筆有效 Token" ($firstTokenId -ne $null)

    # 忘記密碼的 email 冷卻限流（預設 60 秒）是 InMemoryRateLimiter 的記憶體狀態，
    # 不落在資料庫裡，沒有任何 DB 操作能繞過它——只能真的等過冷卻時間，
    # 才能讓第二次申請走到「新申請讓舊 Token 作廢」那段程式碼，而不是被限流直接吞掉。
    # 這裡刻意等足 61 秒是預期行為，不是腳本卡住。
    Write-Host "  （等待約 61 秒讓 email 冷卻限流過期，這是預期行為，不是腳本卡住）" -ForegroundColor DarkGray
    Start-Sleep -Seconds 61
    $forgot2 = Invoke-ApiCall -Method Post -Path "/api/auth/forgot-password" -Body @{ email = $emailB }
    Test-Case "第二次忘記密碼申請（冷卻時間後）回 202" ($forgot2.StatusCode -eq 202)
    Start-Sleep -Milliseconds 200

    $firstTokenInvalidatedAt = Invoke-SqlScalar -Sql "SELECT invalidated_at FROM password_reset_token WHERE id = @id" -Params @{ id = $firstTokenId }
    Test-Case "新申請讓第一筆舊 Token 被標記作廢" ($firstTokenInvalidatedAt -ne [DBNull]::Value)

    $stillValidCount = Invoke-SqlScalar -Sql @"
SELECT COUNT(*) FROM password_reset_token
    WHERE account_id = @id AND used_at IS NULL AND invalidated_at IS NULL
"@ -Params @{ id = $accountIdB }
    Test-Case "同帳號仍算有效的 Token 只剩最新那一筆" ([int]$stillValidCount -eq 1)

    # ------------------------------------------------------------------
    # 第九段（帳號 B）：同一個 Token 兩個並行請求只有一個成功（悲觀鎖）
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 並行重設：同一 Token 只有一個請求成功 ---" -ForegroundColor Cyan

    $rawTokenC = New-RawResetToken
    $tokenHashC = Get-Sha256Hex -Text $rawTokenC
    Invoke-SqlNonQuery -Sql @"
INSERT INTO password_reset_token (account_id, token_hash, expires_at, created_at)
VALUES (@accountId, @tokenHash, DATEADD(MINUTE, 15, SYSDATETIME()), SYSDATETIME())
"@ -Params @{ accountId = $accountIdB; tokenHash = $tokenHashC } | Out-Null

    # Windows PowerShell 5.1 預設不會自動載入 System.Net.Http，要手動 Add-Type 才拿得到型別
    Add-Type -AssemblyName System.Net.Http
    $httpClient = New-Object System.Net.Http.HttpClient
    try {
        $body1 = New-Object System.Net.Http.StringContent(
            (@{ token = $rawTokenC; newPassword = "E2eRace@01"; confirmPassword = "E2eRace@01" } | ConvertTo-Json),
            [System.Text.Encoding]::UTF8, "application/json")
        $body2 = New-Object System.Net.Http.StringContent(
            (@{ token = $rawTokenC; newPassword = "E2eRace@02"; confirmPassword = "E2eRace@02" } | ConvertTo-Json),
            [System.Text.Encoding]::UTF8, "application/json")

        $task1 = $httpClient.PostAsync("$BaseUrl/api/auth/reset-password", $body1)
        $task2 = $httpClient.PostAsync("$BaseUrl/api/auth/reset-password", $body2)
        [System.Threading.Tasks.Task]::WaitAll(@($task1, $task2))

        $status1 = [int]$task1.Result.StatusCode
        $status2 = [int]$task2.Result.StatusCode
        # 刻意先用 @() 把兩個狀態碼組成陣列再送進 pipeline——逗號運算子在管線裡的優先權
        # 容易讓人誤讀，寫成 @($a, $b) | Where-Object 才能保證兩個值都真的進了篩選
        $statuses = @($status1, $status2)
        $successCount = ($statuses | Where-Object { $_ -eq 204 }).Count

        Test-Case "同一個 Token 的兩個並行重設請求只有一個成功" ($successCount -eq 1) "狀態碼=$status1,$status2"
    } finally {
        $httpClient.Dispose()
    }

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
