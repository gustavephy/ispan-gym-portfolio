<#
.SYNOPSIS
    Google OAuth 核心（首次註冊）功能的真實 SQL Server opt-in E2E 驗證腳本。

.DESCRIPTION
    只涵蓋 Mockito 測不到的兩個真實行為（見 memory/google_oauth_phase_scope.md 的必補案例）：

      1. 同一張 Registration Ticket 被兩個並行請求提交，悲觀鎖保證最多一個成功。
      2. 建立途中（OauthAccount 唯一約束衝突）失敗時，Account／Member／OauthAccount／
         ExchangeCode 全部真的 rollback，Ticket 保持 PENDING（不是卡在 USED 卻沒有帳號
         的不可回復狀態——這正是這次 code review P1 修正要保證的事）。

    刻意不擴大範圍去涵蓋 LOGIN callback、LINK／UNLINK、限流或清理——那些不在這次要收斂
    的範圍內（見同一份 memory 的分支定位）。比照 scripts/test-password-management-e2e.ps1
    的安全慣例：預設唯讀預覽，需要 -ConfirmTestDatabase 才真的執行；只操作帶本次執行專屬
    前綴的測試資料；不執行任何 DROP／ALTER／TRUNCATE；結束前一律在 finally 清理。

    需要後端已經在跑（本機執行 `mvnw spring-boot:run`，預設 http://localhost:8080）——
    這支腳本不負責啟動/停止後端，跟 password 那支的既有慣例一致。

.PARAMETER BaseUrl
    後端 API 的 base URL。刻意要求是 localhost／127.0.0.1，避免不小心打到別的環境。

.PARAMETER ConfirmTestDatabase
    唯一能讓腳本真的執行寫入操作的開關。沒有這個參數，腳本只會做唯讀的連線資訊預覽。

.PARAMETER EnvFile
    .env 檔案路徑，預設是這支腳本所在目錄的上一層（專案根目錄）。

.EXAMPLE
    # 只看看會連到哪個 DB，不執行任何動作
    .\test-google-oauth-core-e2e.ps1

.EXAMPLE
    # 確認過上面印出的 DB 資訊之後，真的執行
    .\test-google-oauth-core-e2e.ps1 -ConfirmTestDatabase
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
    # 部分非互動式主控台不允許設定 OutputEncoding，失敗就算了，純粹是顯示美觀問題
}

if ([string]::IsNullOrEmpty($EnvFile)) {
    $EnvFile = Join-Path (Split-Path -Parent $PSCommandPath) "..\.env"
}

# ============================================================================
# 共用工具（比照 test-password-management-e2e.ps1 的既有慣例，同一個規則各自複製一份，
# 這個專案目前沒有 PowerShell 共用模組機制，兩支腳本各自獨立、互不 import）
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

# 比照後端 OpaqueTokenGenerator.generate()：32 bytes、Base64 URL-safe、無 padding
function New-RawOpaqueToken {
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
    return [pscustomobject]@{ ServerHost = $serverHost; Port = $port; Database = $databaseName }
}

function Assert-NotLikelyProduction {
    param([Parameter(Mandatory)][string]$BaseUrl, [Parameter(Mandatory)]$DbInfo)
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
        throw "安全防呆：拒絕對疑似正式環境執行寫入測試"
    }
}

$script:SqlConnectionString = $null

function Connect-TestDatabase {
    param([Parameter(Mandatory)]$DbInfo, [Parameter(Mandatory)][string]$Username, [Parameter(Mandatory)][string]$Password)
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

function Invoke-SqlInsertGetIdentity {
    param([Parameter(Mandatory)][string]$Sql, [hashtable]$Params = @{})
    return [int](Invoke-SqlScalar -Sql "$Sql; SELECT SCOPE_IDENTITY();" -Params $Params)
}

function Invoke-ApiCall {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        $Body = $null
    )
    $uri = "$BaseUrl$Path"
    $params = @{
        Method  = $Method
        Uri     = $uri
        ErrorAction = "Stop"
        # Windows PowerShell 5.1 的 Invoke-WebRequest 若不加這個，在沒完成過 IE 首次執行
        # 設定的機器上會直接丟 NullReferenceException，跟目標伺服器無關
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
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
        if ($null -eq $resp) { throw }
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

$script:TotalCases = 0
$script:FailedCases = 0

function Test-Case {
    param([Parameter(Mandatory)][string]$Description, [Parameter(Mandatory)][bool]$Condition, [string]$Detail = $null)
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

function Remove-TestData {
    param([Parameter(Mandatory)][string]$EmailPrefix)
    if (-not $script:SqlConnectionString) { return }
    Write-Host ""
    Write-Host "清理測試資料（前綴：$EmailPrefix）..." -ForegroundColor Cyan
    try {
        $accountIdFilter = "SELECT account_id FROM account WHERE email LIKE @prefix"
        $prefixParam = @{ prefix = "$EmailPrefix%" }

        Invoke-SqlNonQuery -Sql "DELETE FROM oauth_exchange_code WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM oauth_account WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM account_role WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM member WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        # Registration ticket 沒有 account_id（首次註冊前根本還沒有 Account），要另外用
        # provider_email 篩選——不論這次流程最後有沒有真的建出 Account 都要清得掉。
        Invoke-SqlNonQuery -Sql "DELETE FROM oauth_registration_ticket WHERE provider_email LIKE @prefix" -Params $prefixParam | Out-Null
        $deleted = Invoke-SqlNonQuery -Sql "DELETE FROM account WHERE email LIKE @prefix" -Params $prefixParam
        Write-Host "  已刪除 $deleted 筆 account（及其關聯的 oauth_account／member／account_role／exchange_code）" -ForegroundColor Cyan
    } catch {
        Write-Host "  清理時發生錯誤（可能需要手動檢查殘留資料）：$($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# ============================================================================
# 主流程
# ============================================================================

Write-Host "=== Google OAuth 核心（首次註冊）真實 DB E2E ===" -ForegroundColor Cyan

$envVars = Read-DotEnv -Path $EnvFile
$dbUrl = if ($envVars.ContainsKey("DB_URL")) { $envVars["DB_URL"] } else {
    "jdbc:sqlserver://localhost:1433;databaseName=ispan-proj-gymdb;encrypt=false;trustServerCertificate=true"
}
$dbInfo = ConvertFrom-JdbcSqlServerUrl -JdbcUrl $dbUrl
$dbUsername = if ($envVars.ContainsKey("DB_USERNAME")) { $envVars["DB_USERNAME"] } else { "sa" }
if (-not $envVars.ContainsKey("DB_PASSWORD")) {
    throw ".env 缺少 DB_PASSWORD"
}
$dbPassword = $envVars["DB_PASSWORD"]

Write-Host ""
Write-Host "將連線到：" -ForegroundColor Yellow
Write-Host "  Server:   $($dbInfo.ServerHost):$($dbInfo.Port)"
Write-Host "  Database: $($dbInfo.Database)"
Write-Host "  Username: $($dbUsername.Substring(0, [Math]::Min(2, $dbUsername.Length)))**（僅顯示前 2 碼）"
Write-Host "  BaseUrl:  $BaseUrl"
Write-Host ""

if (-not $ConfirmTestDatabase) {
    Write-Host "唯讀預覽模式（未加 -ConfirmTestDatabase），不執行任何動作。" -ForegroundColor Yellow
    exit 0
}

Assert-NotLikelyProduction -BaseUrl $BaseUrl -DbInfo $dbInfo
Connect-TestDatabase -DbInfo $dbInfo -Username $dbUsername -Password $dbPassword

$runId = New-RunId
$emailPrefix = "oauth-e2e-$runId"
Write-Host "本次執行 runId：$runId（測試 email 前綴：$emailPrefix-*@test.local）" -ForegroundColor Cyan

try {
    # ------------------------------------------------------------------
    # 案例一：同一張 Registration Ticket 被兩個並行請求提交，最多一個成功
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 併發提交同一張 Registration Ticket ---" -ForegroundColor Cyan

    $rawTokenA = New-RawOpaqueToken
    $tokenHashA = Get-Sha256Hex -Text $rawTokenA
    $subjectA = "$emailPrefix-sub-concurrent"
    $emailA = "$emailPrefix-concurrent@test.local"

    Invoke-SqlNonQuery -Sql @"
INSERT INTO oauth_registration_ticket
    (token_hash, oauth_provider, provider_user_id, provider_email, provider_email_verified,
     suggested_first_name, suggested_last_name, status, expires_at, created_at)
VALUES
    (@tokenHash, 'GOOGLE', @subject, @email, 1, N'小明', N'王', 'PENDING',
     DATEADD(MINUTE, 15, SYSDATETIME()), SYSDATETIME())
"@ -Params @{ tokenHash = $tokenHashA; subject = $subjectA; email = $emailA } | Out-Null

    Add-Type -AssemblyName System.Net.Http
    $httpClient = New-Object System.Net.Http.HttpClient
    try {
        $bodyJson1 = @{ registrationToken = $rawTokenA; firstName = "小明"; lastName = "王"; phone = "0911111111" } | ConvertTo-Json
        $bodyJson2 = @{ registrationToken = $rawTokenA; firstName = "大明"; lastName = "李"; phone = "0922222222" } | ConvertTo-Json
        $content1 = New-Object System.Net.Http.StringContent($bodyJson1, [System.Text.Encoding]::UTF8, "application/json")
        $content2 = New-Object System.Net.Http.StringContent($bodyJson2, [System.Text.Encoding]::UTF8, "application/json")

        $task1 = $httpClient.PostAsync("$BaseUrl/api/auth/oauth/google/registration/complete", $content1)
        $task2 = $httpClient.PostAsync("$BaseUrl/api/auth/oauth/google/registration/complete", $content2)
        [System.Threading.Tasks.Task]::WaitAll(@($task1, $task2))

        $status1 = [int]$task1.Result.StatusCode
        $status2 = [int]$task2.Result.StatusCode
        $statuses = @($status1, $status2)
        $successCount = ($statuses | Where-Object { $_ -eq 200 }).Count

        Test-Case "同一張 Registration Ticket 的兩個並行請求只有一個成功(200)" ($successCount -eq 1) "狀態碼=$status1,$status2"
    } finally {
        $httpClient.Dispose()
    }

    $accountCountA = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM account WHERE email = @email" -Params @{ email = $emailA }
    Test-Case "併發只建立了一筆 Account" ([int]$accountCountA -eq 1) "實際=$accountCountA"

    $oauthAccountCountA = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM oauth_account WHERE oauth_provider = 'GOOGLE' AND provider_user_id = @subject" -Params @{ subject = $subjectA }
    Test-Case "併發只建立了一筆 OauthAccount 綁定" ([int]$oauthAccountCountA -eq 1) "實際=$oauthAccountCountA"

    $ticketStatusA = Invoke-SqlScalar -Sql "SELECT status FROM oauth_registration_ticket WHERE token_hash = @tokenHash" -Params @{ tokenHash = $tokenHashA }
    Test-Case "Ticket 最終狀態為 USED（贏家那次真的消耗掉了）" ($ticketStatusA -eq "USED") "實際=$ticketStatusA"

    $exchangeCodeCountA = Invoke-SqlScalar -Sql @"
SELECT COUNT(*) FROM oauth_exchange_code c
JOIN account a ON a.account_id = c.account_id
WHERE a.email = @email
"@ -Params @{ email = $emailA }
    Test-Case "只有贏家那次核發了交換碼（輸家在鎖內重讀到 USED，什麼都沒建立）" ([int]$exchangeCodeCountA -eq 1) "實際=$exchangeCodeCountA"

    # ------------------------------------------------------------------
    # 案例二：建立途中（OauthAccount 唯一約束衝突）失敗，全部真的 rollback
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 建立途中失敗時 Account／Member／OauthAccount／ExchangeCode 全部 rollback ---" -ForegroundColor Cyan

    # 先建一個「已經完成註冊」的錨點帳號，佔用某個 (GOOGLE, subject) 綁定——
    # 新的 registration ticket 會用同一個 subject，逼 OauthAccount.save() 在
    # (oauth_provider, provider_user_id) 唯一約束上真的撞上，而且是在 Account／Member
    # 都已經成功寫入「之後」才撞——這正是要驗證「後段失敗、前段也要跟著 rollback」的關鍵。
    $anchorEmail = "$emailPrefix-anchor@test.local"
    $subjectB = "$emailPrefix-sub-rollback"
    $anchorAccountId = Invoke-SqlInsertGetIdentity -Sql @"
INSERT INTO account (email, password_hash, credentials_version, created_at, updated_at)
VALUES (@email, NULL, 0, SYSDATETIME(), SYSDATETIME())
"@ -Params @{ email = $anchorEmail }
    Invoke-SqlNonQuery -Sql @"
INSERT INTO oauth_account (account_id, oauth_provider, provider_user_id, provider_email, provider_email_verified, last_login_at, created_at)
VALUES (@accountId, 'GOOGLE', @subject, @email, 1, SYSDATETIME(), SYSDATETIME())
"@ -Params @{ accountId = $anchorAccountId; subject = $subjectB; email = $anchorEmail } | Out-Null

    $rawTokenB = New-RawOpaqueToken
    $tokenHashB = Get-Sha256Hex -Text $rawTokenB
    $emailB = "$emailPrefix-rollback@test.local"
    Invoke-SqlNonQuery -Sql @"
INSERT INTO oauth_registration_ticket
    (token_hash, oauth_provider, provider_user_id, provider_email, provider_email_verified,
     suggested_first_name, suggested_last_name, status, expires_at, created_at)
VALUES
    (@tokenHash, 'GOOGLE', @subject, @email, 1, N'小華', N'陳', 'PENDING',
     DATEADD(MINUTE, 15, SYSDATETIME()), SYSDATETIME())
"@ -Params @{ tokenHash = $tokenHashB; subject = $subjectB; email = $emailB } | Out-Null

    $completeBody = @{ registrationToken = $rawTokenB; firstName = "小華"; lastName = "陳"; phone = "0933333333" }
    $result = Invoke-ApiCall -Method POST -Path "/api/auth/oauth/google/registration/complete" -Body $completeBody

    Test-Case "衝突時回傳 400（不是 500，Conflict Resolver 正確接手分類）" ($result.StatusCode -eq 400) "實際=$($result.StatusCode)"
    Test-Case "衝突原因分類為 Ticket 無效（sub 已被搶先綁定），不是通用未知錯誤" (
        $result.Body -and $result.Body.code -eq "OAUTH_REGISTRATION_TICKET_INVALID"
    ) "實際 code=$($result.Body.code)"

    $newAccountCountB = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM account WHERE email = @email" -Params @{ email = $emailB }
    Test-Case "衝突失敗後，新 Account 沒有殘留（真的 rollback，不是半套資料）" ([int]$newAccountCountB -eq 0) "實際=$newAccountCountB"

    $oauthAccountCountB = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM oauth_account WHERE oauth_provider = 'GOOGLE' AND provider_user_id = @subject" -Params @{ subject = $subjectB }
    Test-Case "衝突失敗後，該 subject 仍只有原本錨點那一筆綁定" ([int]$oauthAccountCountB -eq 1) "實際=$oauthAccountCountB"

    $ticketStatusB = Invoke-SqlScalar -Sql "SELECT status FROM oauth_registration_ticket WHERE token_hash = @tokenHash" -Params @{ tokenHash = $tokenHashB }
    Test-Case "衝突失敗後，Ticket 仍保持 PENDING（可以重試，不是卡死在 USED）" ($ticketStatusB -eq "PENDING") "實際=$ticketStatusB"
    $ticketFinalizedB = Invoke-SqlScalar -Sql "SELECT finalized_at FROM oauth_registration_ticket WHERE token_hash = @tokenHash" -Params @{ tokenHash = $tokenHashB }
    Test-Case "衝突失敗後，Ticket 的 finalized_at 仍是 NULL" ([System.DBNull]::Value.Equals($ticketFinalizedB) -or $null -eq $ticketFinalizedB)

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
