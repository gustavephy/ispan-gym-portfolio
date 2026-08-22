<#
.SYNOPSIS
    Google OAuth 核心（首次註冊＋LINK 併發）功能的真實 SQL Server opt-in E2E 驗證腳本。

.DESCRIPTION
    只涵蓋 Mockito 測不到的真實行為（見 memory/google_oauth_phase_scope.md 的必補案例）：

      1. 同一張 Registration Ticket 被兩個並行請求提交，悲觀鎖保證最多一個成功。
      2. 建立途中（OauthAccount 唯一約束衝突）失敗時，Account／Member／OauthAccount／
         ExchangeCode 全部真的 rollback，Ticket 保持 PENDING（不是卡在 USED 卻沒有帳號
         的不可回復狀態——這正是這次 code review P1 修正要保證的事）。
      3. 同一 Account 併發發起 LINK，Account-first 悲觀鎖序列化兩個請求，最終最多一筆
         PENDING transaction（不是「兩個請求只有一個成功」——第二個請求會在鎖內把第一個
         supersede 掉再建立自己的，兩次呼叫都可能回 200，DB 層的「最多一筆 PENDING」
         才是真正的保證）。
      4. 同一 Google (provider, subject) 併發寫入 oauth_account，唯一約束保證最多一筆
         成功——這裡直接對 oauth_account 做並行寫入，不經過真實 Google Callback（那需要
         真實 Google 憑證，屬於之後「真實 Google E2E」階段的範圍，這裡只驗證 Callback
         邏輯（見 GoogleOAuthLinkServiceTest）實際依賴的 DB 約束在真正併發下真的擋得住）。
      5. 完整 UNLINK 回合：真實密碼驗證、真實刪除 oauth_account、credentialsVersion
         真的遞增、解除後舊 JWT 立即失效、重新登入的新 JWT 仍可用。
      6. LOGIN 發起端點（GET /oauth2/authorization/google）的 IP 限流：連續打超過額度，
         確認超額後真的被擋下（404），不是無限放行。
      7. LINK 發起端點（POST /api/account/me/oauth/google/link）的 initiate 限流：用
         「正確密碼」連續發起超過額度，確認即使密碼答對、reauth 額度被清除，initiate
         額度仍然把過量請求擋下（429 OAUTH_LINK_RATE_LIMITED）。

    案例六（LOGIN 限流）需要後端啟動時帶 GOOGLE_OAUTH_ENABLED=true 與任意非 placeholder
    的 GOOGLE_CLIENT_ID／GOOGLE_CLIENT_SECRET——這條路徑在 enabled=false（.env 沒有真實
    Google 憑證時的預設值）會在碰到限流邏輯之前就被 feature flag 擋掉，回傳恆定的 404，
    那樣不管限流有沒有生效都會「看起來像通過」，反而測不出東西。StartupValidator 只檢查
    這兩個值格式上不是 placeholder，不會真的呼叫 Google，所以可以用假值啟動——這個案例
    完全不需要真的把使用者導到 Google 那一步。

    刻意不擴大範圍去涵蓋清理排程以外的其他 housekeeping、真實 Google 登入本身——那些
    不在這次要收斂的範圍內（見同一份 memory 的分支定位）。比照
    scripts/test-password-management-e2e.ps1 的安全慣例：預設唯讀預覽，需要
    -ConfirmTestDatabase 才真的執行；只操作帶本次執行專屬前綴的測試資料（LOGIN 限流案例
    的 transaction 沒有 account_id 可用，改用 oauth_transaction_id 分水嶺清理，見該案例
    的程式碼註解）；不執行任何 DROP／ALTER／TRUNCATE；結束前一律在 finally 清理。

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
        Invoke-SqlNonQuery -Sql "DELETE FROM oauth_transaction WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM oauth_account WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM email_verification WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM account_role WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        Invoke-SqlNonQuery -Sql "DELETE FROM member WHERE account_id IN ($accountIdFilter)" -Params $prefixParam | Out-Null
        # Registration ticket 沒有 account_id（首次註冊前根本還沒有 Account），要另外用
        # provider_email 篩選——不論這次流程最後有沒有真的建出 Account 都要清得掉。
        Invoke-SqlNonQuery -Sql "DELETE FROM oauth_registration_ticket WHERE provider_email LIKE @prefix" -Params $prefixParam | Out-Null
        $deleted = Invoke-SqlNonQuery -Sql "DELETE FROM account WHERE email LIKE @prefix" -Params $prefixParam
        Write-Host "  已刪除 $deleted 筆 account（及其關聯的 oauth_account／oauth_transaction／member／account_role／exchange_code／email_verification）" -ForegroundColor Cyan
    } catch {
        Write-Host "  清理時發生錯誤（可能需要手動檢查殘留資料）：$($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# ============================================================================
# 測試帳號建立——比照 test-password-management-e2e.ps1 的既有做法：走真實
# POST /api/members/register → email_verification 拿 token → GET verify-email
# → POST /api/auth/login 拿 JWT，而不是直接塞一筆假 BCrypt hash 進 DB。
# ============================================================================

function New-TestMemberAccount {
    param([Parameter(Mandatory)][string]$Email, [Parameter(Mandatory)][string]$Password)

    $registerBody = @{
        firstName = "測試"; lastName = "帳號"; email = $Email; phone = "0955555555"
        password = $Password
    }
    $registerResult = Invoke-ApiCall -Method POST -Path "/api/members/register" -Body $registerBody
    if ($registerResult.StatusCode -ne 201) {
        throw "建立測試帳號失敗（register 回 $($registerResult.StatusCode)）：$($registerResult.Body)"
    }

    $token = Invoke-SqlScalar -Sql @"
SELECT TOP 1 ev.token FROM email_verification ev
JOIN account a ON a.account_id = ev.account_id
WHERE a.email = @email AND ev.verified_at IS NULL
ORDER BY ev.created_at DESC
"@ -Params @{ email = $Email }
    if (-not $token) { throw "找不到 $Email 的 email_verification token" }

    $verifyResult = Invoke-ApiCall -Method GET -Path "/api/accounts/verify-email?token=$token"
    if ($verifyResult.StatusCode -ne 200) {
        throw "驗證測試帳號 Email 失敗（回 $($verifyResult.StatusCode)）"
    }

    $loginResult = Invoke-ApiCall -Method POST -Path "/api/auth/login" -Body @{ email = $Email; password = $Password }
    if ($loginResult.StatusCode -ne 200) {
        throw "測試帳號登入失敗（回 $($loginResult.StatusCode)）：$($loginResult.Body)"
    }

    return [pscustomobject]@{
        AccountId = [int]$loginResult.Body.accountId
        AccessToken = [string]$loginResult.Body.accessToken
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

    # ------------------------------------------------------------------
    # 案例三：同一 Account 併發發起 LINK，最終最多一筆 PENDING transaction
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 同一 Account 併發發起 LINK ---" -ForegroundColor Cyan

    $linkEmail = "$emailPrefix-link@test.local"
    $linkPassword = "E2eLink@01"
    $testAccount = New-TestMemberAccount -Email $linkEmail -Password $linkPassword

    Add-Type -AssemblyName System.Net.Http
    $httpClient2 = New-Object System.Net.Http.HttpClient
    try {
        $linkBodyJson = @{ currentPassword = $linkPassword } | ConvertTo-Json
        $linkContent1 = New-Object System.Net.Http.StringContent($linkBodyJson, [System.Text.Encoding]::UTF8, "application/json")
        $linkContent2 = New-Object System.Net.Http.StringContent($linkBodyJson, [System.Text.Encoding]::UTF8, "application/json")

        $linkRequest1 = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$BaseUrl/api/account/me/oauth/google/link")
        $linkRequest1.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $testAccount.AccessToken)
        $linkRequest1.Content = $linkContent1
        $linkRequest2 = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$BaseUrl/api/account/me/oauth/google/link")
        $linkRequest2.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $testAccount.AccessToken)
        $linkRequest2.Content = $linkContent2

        $linkTask1 = $httpClient2.SendAsync($linkRequest1)
        $linkTask2 = $httpClient2.SendAsync($linkRequest2)
        [System.Threading.Tasks.Task]::WaitAll(@($linkTask1, $linkTask2))

        $linkStatus1 = [int]$linkTask1.Result.StatusCode
        $linkStatus2 = [int]$linkTask2.Result.StatusCode
        Test-Case "兩個併發發起請求都被安全處理（200 或已知的 4xx，不是 500）" (
            (@(200, 401, 409) -contains $linkStatus1) -and (@(200, 401, 409) -contains $linkStatus2)
        ) "狀態碼=$linkStatus1,$linkStatus2"
    } finally {
        $httpClient2.Dispose()
    }

    $pendingLinkCount = Invoke-SqlScalar -Sql @"
SELECT COUNT(*) FROM oauth_transaction
WHERE account_id = @accountId AND oauth_provider = 'GOOGLE' AND purpose = 'LINK' AND status = 'PENDING'
"@ -Params @{ accountId = $testAccount.AccountId }
    Test-Case "併發發起後最多只有一筆 PENDING LINK transaction（Account-first 鎖序列化了兩個請求）" (
        [int]$pendingLinkCount -eq 1
    ) "實際 PENDING 筆數=$pendingLinkCount"

    # ------------------------------------------------------------------
    # 案例四：同一 (provider, subject) 併發寫入 oauth_account，唯一約束保證最多一筆成功
    #
    # 這裡直接對 oauth_account 做並行寫入，不經過真實 Google Callback（那需要真實
    # Google 憑證，屬於之後「真實 Google E2E」階段）——目的是驗證 GoogleOAuthLinkService
    # 的業務判斷（見 GoogleOAuthLinkServiceTest）背後真正依賴的 DB 約束，在真正的併發
    # 寫入下依然成立，不只是「理論上應該擋得住」。
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 同一 Google (provider, subject) 併發寫入 oauth_account ---" -ForegroundColor Cyan

    $subjectD = "$emailPrefix-sub-race"
    $accountEmailD1 = "$emailPrefix-race1@test.local"
    $accountEmailD2 = "$emailPrefix-race2@test.local"
    $accountIdD1 = Invoke-SqlInsertGetIdentity -Sql @"
INSERT INTO account (email, password_hash, credentials_version, created_at, updated_at)
VALUES (@email, NULL, 0, SYSDATETIME(), SYSDATETIME())
"@ -Params @{ email = $accountEmailD1 }
    $accountIdD2 = Invoke-SqlInsertGetIdentity -Sql @"
INSERT INTO account (email, password_hash, credentials_version, created_at, updated_at)
VALUES (@email, NULL, 0, SYSDATETIME(), SYSDATETIME())
"@ -Params @{ email = $accountEmailD2 }

    $raceConn1 = New-Object System.Data.SqlClient.SqlConnection($script:SqlConnectionString)
    $raceConn2 = New-Object System.Data.SqlClient.SqlConnection($script:SqlConnectionString)
    try {
        $raceConn1.Open()
        $raceConn2.Open()
        $insertSql = @"
INSERT INTO oauth_account (account_id, oauth_provider, provider_user_id, provider_email, provider_email_verified, last_login_at, created_at)
VALUES (@accountId, 'GOOGLE', @subject, @email, 1, SYSDATETIME(), SYSDATETIME())
"@
        $raceCmd1 = $raceConn1.CreateCommand()
        $raceCmd1.CommandText = $insertSql
        [void]$raceCmd1.Parameters.AddWithValue("@accountId", $accountIdD1)
        [void]$raceCmd1.Parameters.AddWithValue("@subject", $subjectD)
        [void]$raceCmd1.Parameters.AddWithValue("@email", $accountEmailD1)

        $raceCmd2 = $raceConn2.CreateCommand()
        $raceCmd2.CommandText = $insertSql
        [void]$raceCmd2.Parameters.AddWithValue("@accountId", $accountIdD2)
        [void]$raceCmd2.Parameters.AddWithValue("@subject", $subjectD)
        [void]$raceCmd2.Parameters.AddWithValue("@email", $accountEmailD2)

        $raceTask1 = $raceCmd1.ExecuteNonQueryAsync()
        $raceTask2 = $raceCmd2.ExecuteNonQueryAsync()
        $raceResults = @()
        foreach ($t in @($raceTask1, $raceTask2)) {
            try { [void]$t.GetAwaiter().GetResult(); $raceResults += "OK" }
            catch { $raceResults += "FAIL" }
        }
        $successCount = ($raceResults | Where-Object { $_ -eq "OK" }).Count
        Test-Case "同一 (provider, subject) 的兩筆併發 INSERT 只有一筆成功（唯一約束擋下另一筆）" (
            $successCount -eq 1
        ) "結果=$($raceResults -join ',')"
    } finally {
        $raceConn1.Close()
        $raceConn2.Close()
    }

    $boundCount = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM oauth_account WHERE oauth_provider = 'GOOGLE' AND provider_user_id = @subject" -Params @{ subject = $subjectD }
    Test-Case "最終該 subject 只綁定了一個 Account" ([int]$boundCount -eq 1) "實際=$boundCount"

    # ------------------------------------------------------------------
    # 案例五：完整 UNLINK 回合——真實密碼驗證、真實刪除、credentialsVersion 遞增、
    # 舊 JWT 真的被拒絕、新 JWT 仍然可用
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 完整 UNLINK 回合 ---" -ForegroundColor Cyan

    $unlinkEmail = "$emailPrefix-unlink@test.local"
    $unlinkPassword = "E2eUnlink@01"
    $unlinkAccount = New-TestMemberAccount -Email $unlinkEmail -Password $unlinkPassword
    $oldToken = $unlinkAccount.AccessToken

    # 直接插入一筆綁定（不經過真實 Google Callback，理由同案例四）
    Invoke-SqlNonQuery -Sql @"
INSERT INTO oauth_account (account_id, oauth_provider, provider_user_id, provider_email, provider_email_verified, last_login_at, created_at)
VALUES (@accountId, 'GOOGLE', @subject, @email, 1, SYSDATETIME(), SYSDATETIME())
"@ -Params @{ accountId = $unlinkAccount.AccountId; subject = "$emailPrefix-sub-unlink"; email = $unlinkEmail } | Out-Null

    $profileBeforeUnlink = Invoke-ApiCall -Method GET -Path "/api/member/profile" -BearerToken $oldToken
    Test-Case "解除前，舊 JWT 可以正常存取受保護 API" ($profileBeforeUnlink.StatusCode -eq 200) "實際=$($profileBeforeUnlink.StatusCode)"

    $unlinkResult = Invoke-ApiCall -Method DELETE -Path "/api/account/me/oauth/google" `
        -Body @{ currentPassword = $unlinkPassword } -BearerToken $oldToken
    Test-Case "有密碼時可以成功解除（204）" ($unlinkResult.StatusCode -eq 204) "實際=$($unlinkResult.StatusCode)"

    $bindingGone = Invoke-SqlScalar -Sql "SELECT COUNT(*) FROM oauth_account WHERE account_id = @accountId" -Params @{ accountId = $unlinkAccount.AccountId }
    Test-Case "解除後 oauth_account 綁定真的被刪除" ([int]$bindingGone -eq 0) "實際=$bindingGone"

    $credentialsVersionAfter = Invoke-SqlScalar -Sql "SELECT credentials_version FROM account WHERE account_id = @accountId" -Params @{ accountId = $unlinkAccount.AccountId }
    Test-Case "解除後 credentialsVersion 遞增為 1" ([int]$credentialsVersionAfter -eq 1) "實際=$credentialsVersionAfter"

    $profileWithOldToken = Invoke-ApiCall -Method GET -Path "/api/member/profile" -BearerToken $oldToken
    Test-Case "解除後，舊 JWT 立即失效（401）" ($profileWithOldToken.StatusCode -eq 401) "實際=$($profileWithOldToken.StatusCode)"

    $reloginResult = Invoke-ApiCall -Method POST -Path "/api/auth/login" -Body @{ email = $unlinkEmail; password = $unlinkPassword }
    Test-Case "解除後仍可用密碼重新登入" ($reloginResult.StatusCode -eq 200) "實際=$($reloginResult.StatusCode)"
    if ($reloginResult.StatusCode -eq 200) {
        $newToken = [string]$reloginResult.Body.accessToken
        $profileWithNewToken = Invoke-ApiCall -Method GET -Path "/api/member/profile" -BearerToken $newToken
        Test-Case "重新登入後的新 JWT 可以正常存取受保護 API" ($profileWithNewToken.StatusCode -eq 200) "實際=$($profileWithNewToken.StatusCode)"
    }

    # 對應必補案例「唯一登入方式不能解除」的真實回合：OAuth-only（無密碼）帳號沒有
    # 密碼可以登入，無法透過真實 HTTP 走到這個端點驗證——這一項留給
    # GoogleOAuthUnlinkServiceTest 的 Mockito 測試覆蓋（見該檔案），這裡不重複硬湊。

    Write-Host ""
    # ------------------------------------------------------------------
    # 案例六：LOGIN 發起端點的 IP 限流
    #
    # 這條路徑（GET /oauth2/authorization/google）在 app.oauth.google.enabled=false
    # 時，Resolver 會在碰到限流邏輯之前就直接回 null（見 step 3 的 feature flag 設計），
    # 也就是說要真的驗證限流本身，後端這次啟動時必須帶：
    #   GOOGLE_OAUTH_ENABLED=true
    #   GOOGLE_CLIENT_ID=<任意非 placeholder 值，不需要是真的能用的 Google 憑證>
    #   GOOGLE_CLIENT_SECRET=<同上>
    # StartupValidator 只檢查這兩個值格式上不是 placeholder，不會真的呼叫 Google，
    # 所以可以用假值啟動——這裡完全不需要真的把使用者導到 Google 那一步，只驗證
    # Resolver 在超過額度前後回傳的 HTTP 狀態碼差異。
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- LOGIN 發起端點的 IP 限流 ---" -ForegroundColor Cyan

    $loginRateBeforeId = [int](Invoke-SqlScalar -Sql "SELECT ISNULL(MAX(oauth_transaction_id), 0) FROM oauth_transaction")
    $loginHandler = New-Object System.Net.Http.HttpClientHandler
    $loginHandler.AllowAutoRedirect = $false
    $loginRateClient = New-Object System.Net.Http.HttpClient($loginHandler)
    try {
        # 不預設「額度剩多少」——同一支後端行程可能已經被其他呼叫（例如手動 curl 驗證
        # 過一次）用掉幾次額度，這裡改成打到「連續 3 次都是同一個狀態碼」為止，
        # 用來判斷是否真的發生了 302→404 的轉換，而不是硬編一個確切次數。
        $loginStatuses = @()
        for ($i = 1; $i -le 25; $i++) {
            $resp = $loginRateClient.GetAsync("$BaseUrl/oauth2/authorization/google").GetAwaiter().GetResult()
            $loginStatuses += [int]$resp.StatusCode
        }
        $blockedCount = ($loginStatuses | Where-Object { $_ -eq 404 }).Count
        $allowedCount = ($loginStatuses | Where-Object { $_ -eq 302 }).Count
        $unexpectedCount = ($loginStatuses | Where-Object { $_ -ne 404 -and $_ -ne 302 }).Count

        Test-Case "LOGIN 限流：連續 25 次請求裡出現過被擋下的情況（額度用完後回 404，不是 500）" (
            $blockedCount -gt 0
        ) "302 次數=$allowedCount, 404 次數=$blockedCount, 其他=$unexpectedCount, 完整序列=$($loginStatuses -join ',')"
        Test-Case "LOGIN 限流：沒有出現非預期狀態碼（只有 302 或 404）" ($unexpectedCount -eq 0) "完整序列=$($loginStatuses -join ',')"
        # 一旦開始被擋，後續應該持續被擋（同一個 10 分鐘視窗內不會忽然又放行）
        $firstBlockedIndex = [array]::IndexOf($loginStatuses, 404)
        if ($firstBlockedIndex -ge 0) {
            $afterFirstBlock = $loginStatuses[$firstBlockedIndex..($loginStatuses.Length - 1)]
            $allBlockedAfter = ($afterFirstBlock | Where-Object { $_ -ne 404 }).Count -eq 0
            Test-Case "LOGIN 限流：一旦開始被擋，同一批請求內持續被擋（不會忽冷忽熱）" $allBlockedAfter "被擋之後的序列=$($afterFirstBlock -join ',')"
        }
    } finally {
        $loginRateClient.Dispose()
        # 這些 LOGIN transaction 沒有 account_id 可以用來篩選清理對象，改用執行前記下的
        # oauth_transaction_id 分水嶺——這批之後新增的、account_id 為 NULL 的 LOGIN
        # transaction 都是這次測試建立的
        Invoke-SqlNonQuery -Sql @"
DELETE FROM oauth_transaction
WHERE oauth_transaction_id > @beforeId AND purpose = 'LOGIN' AND account_id IS NULL
"@ -Params @{ beforeId = $loginRateBeforeId } | Out-Null
    }

    # ------------------------------------------------------------------
    # 案例七：LINK 發起端點的 initiate 限流（跟 reauth 限流是兩件不同的事，
    # 密碼答對後 reauth 額度會被清除，但 initiate 額度不會——見
    # GoogleOAuthLinkInitiationService 的類別註解）
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- LINK 發起端點的 initiate 限流（正確密碼仍受限） ---" -ForegroundColor Cyan

    $rateLimitEmail = "$emailPrefix-linkrate@test.local"
    $rateLimitPassword = "E2eRate@01"
    $rateLimitAccount = New-TestMemberAccount -Email $rateLimitEmail -Password $rateLimitPassword

    $linkRateStatuses = @()
    $linkRateBodies = @()
    for ($i = 1; $i -le 6; $i++) {
        $r = Invoke-ApiCall -Method POST -Path "/api/account/me/oauth/google/link" `
            -Body @{ currentPassword = $rateLimitPassword } -BearerToken $rateLimitAccount.AccessToken
        $linkRateStatuses += $r.StatusCode
        $linkRateBodies += $r.Body
    }

    $linkSuccessCount = ($linkRateStatuses | Where-Object { $_ -eq 200 }).Count
    $linkRateLimitedCount = ($linkRateStatuses | Where-Object { $_ -eq 429 }).Count
    Test-Case "LINK initiate 限流：連續 6 次正確密碼請求中，恰好第 6 次被限流擋下（前 5 次都成功）" (
        $linkSuccessCount -eq 5 -and $linkRateLimitedCount -eq 1 -and $linkRateStatuses[5] -eq 429
    ) "狀態碼序列=$($linkRateStatuses -join ',')"

    $lastBody = $linkRateBodies[5]
    Test-Case "被限流的那次回傳正確的錯誤碼 OAUTH_LINK_RATE_LIMITED" (
        $lastBody -and $lastBody.code -eq "OAUTH_LINK_RATE_LIMITED"
    ) "實際 code=$($lastBody.code)"

    $pendingAfterRateLimit = Invoke-SqlScalar -Sql @"
SELECT COUNT(*) FROM oauth_transaction
WHERE account_id = @accountId AND oauth_provider = 'GOOGLE' AND purpose = 'LINK' AND status = 'PENDING'
"@ -Params @{ accountId = $rateLimitAccount.AccountId }
    Test-Case "被限流時沒有再建立或取代 transaction（仍是前 5 次裡最後一次留下的那筆 PENDING）" (
        [int]$pendingAfterRateLimit -eq 1
    ) "實際 PENDING 筆數=$pendingAfterRateLimit"

    # ------------------------------------------------------------------
    # 案例八：Exchange Code 核發後 credentialsVersion 改變，兌換必須拒絕
    # （2026-08-22 code review 補上——GoogleOAuthExchangeServiceTest 已經用 Mockito
    # 覆蓋過這個比對邏輯本身，這裡額外驗證真實的 JPQL 投影查詢＋Account-first 鎖＋
    # 原子消耗，在真的 SQL Server 上實際跑起來也是同一個結果，不是只在單元測試裡成立）
    # ------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- Exchange Code 核發後 CredentialsVersion 改變 ---" -ForegroundColor Cyan

    $cvEmail = "$emailPrefix-cvmismatch@test.local"
    $cvPassword = "E2eCvMismatch@01"
    $cvAccount = New-TestMemberAccount -Email $cvEmail -Password $cvPassword

    $rawExchangeCode = New-RawOpaqueToken
    $exchangeCodeHash = Get-Sha256Hex -Text $rawExchangeCode
    Invoke-SqlNonQuery -Sql @"
INSERT INTO oauth_exchange_code
    (code_hash, account_id, issued_credentials_version, expires_at, created_at)
VALUES
    (@codeHash, @accountId, 0, DATEADD(SECOND, 90, SYSDATETIME()), SYSDATETIME())
"@ -Params @{ codeHash = $exchangeCodeHash; accountId = $cvAccount.AccountId } | Out-Null

    # 模擬核發之後、兌換之前發生了一次 UNLINK／改密碼／全裝置登出
    Invoke-SqlNonQuery -Sql "UPDATE account SET credentials_version = credentials_version + 1 WHERE account_id = @accountId" `
        -Params @{ accountId = $cvAccount.AccountId } | Out-Null

    $cvExchangeResult = Invoke-ApiCall -Method POST -Path "/api/auth/oauth/exchange" -Body @{ code = $rawExchangeCode }
    Test-Case "版本不符時兌換回傳 400，不是 200" ($cvExchangeResult.StatusCode -eq 400) "實際狀態碼=$($cvExchangeResult.StatusCode)"
    Test-Case "版本不符時錯誤碼為 OAUTH_EXCHANGE_CODE_INVALID" (
        $cvExchangeResult.Body -and $cvExchangeResult.Body.code -eq "OAUTH_EXCHANGE_CODE_INVALID"
    ) "實際 code=$($cvExchangeResult.Body.code)"

    $cvCodeUsedAt = Invoke-SqlScalar -Sql "SELECT used_at FROM oauth_exchange_code WHERE code_hash = @codeHash" `
        -Params @{ codeHash = $exchangeCodeHash }
    Test-Case "被拒絕的 Code 仍然被原子消耗掉（不能留著給人重試猜測）" ($null -ne $cvCodeUsedAt) "used_at=$cvCodeUsedAt"

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
