# Google OAuth 登入／註冊／綁定 — 設計筆記

`feat/google-oauth` 分支的核心設計決策與不變量整理，供程式內部 Javadoc 引用、也供 PR review 時查閱。這是精簡版參考文件，不是開發過程紀錄；完整的討論脈絡與逐項決策理由不在這裡保存。

## 身分模型

- `Account` 是唯一的身分/角色來源，Google 只是一種登入方式，不建立平行的使用者模型。
- 外部身分的唯一鍵是 `(oauth_provider, provider_user_id)`（Google 的 OIDC `sub`），**永遠不用 email 當查找依據**。
- 同一 email 已存在其他 Account 時，LOGIN 一律回傳 `ACCOUNT_LINK_REQUIRED`，不自動綁定、不洩漏對方帳號狀態。
- 首次 Google 登入只建立 `MEMBER` 角色的 `Account` + `ACTIVE Member`，不會直接建立 `Employee`/`Trainer`/`Admin`。

## 短效憑證表：各自獨立，不合併成一張泛用表

| 表 | 用途 | TTL |
|---|---|---|
| `oauth_transaction` | 一次 Google 授權往返（LOGIN 或 LINK），`state` 的雜湊查找目標 | 5–10 分 |
| `oauth_registration_ticket` | 首次登入補資料期間的暫存身分 | 10–15 分 |
| `oauth_exchange_code` | Callback 成功到前端換發 JWT 之間的一次性交換碼 | 60–120 秒 |

三張表共同的模式：只存雜湊值（SHA-256），原始 token 只在 URL/回應中出現一次；`status`/`used_at`/`expires_at` 由「原子 UPDATE...WHERE 消耗」保證單次使用，不是先查再改的 check-then-act。

## Account-first 全域鎖序

整個帳號安全域（密碼管理 + Google OAuth）共用固定鎖序：

```
Account（PESSIMISTIC_WRITE）→ 短效憑證列（OAuthTransaction／PasswordResetToken／
    OAuthRegistrationTicket／OAuthExchangeCode）→ 依賴資料（OauthAccount／PasswordHistory）
```

只知道 token/state、還不知道 accountId 時，固定流程是：**非鎖定的 scalar 查找拿到 accountId → 鎖 Account → 鎖進來之後重查一次業務狀態是否仍然成立 → 才繼續動作**。這個模式在以下地方都一致套用：

- `PasswordService.resetPassword()`
- `GoogleOAuthLinkInitiationService.initiate()`
- `GoogleOAuthUnlinkService.unlink()`
- `GoogleOAuthLinkService.link()`（LINK callback）
- `GoogleOAuthLoginService.handleExistingBinding()`（LOGIN 既有綁定路徑）
- `GoogleOAuthExchangeService.exchange()`

唯一的例外是首次 Google 註冊（`GoogleOAuthRegistrationCreationService`）：這時還沒有 Account 可以鎖，改用 `OAuthRegistrationTicket` 的悲觀鎖 + DB 唯一約束當作併發防線。

## `credentialsVersion` 與 Exchange Code 的版本快照

`Account.credentialsVersion` 是全裝置登出機制：change/reset password、**OAuth-only 帳號設定第一組本地密碼（`set-password`）**、UNLINK 都會遞增；JWT 簽發時把當下版本放進 `cv` claim，每次請求比對。change password 與 set-password 會在同一回應附一組遞增後版本的新 JWT（目前裝置維持登入）；UNLINK 不核發新 JWT（一律重新登入）。

> STOMP 只在 `CONNECT` frame 比對 `cv`，已建立的連線不會被主動中斷——`cv` 遞增後舊 token 無法再重新連線，但當下的聊天室連線要等它自然斷開或使用者離開頁面才失效。

`OAuthExchangeCode.issuedCredentialsVersion` 是**核發當下**的版本快照（LOGIN 與首次註冊兩個核發點都會寫入）。兌換時的流程：

1. 非鎖定投影查找 `accountId`／核發版本／`returnPath`。
2. 原子消耗（`consumeIfUnused`）——無論後續版本比對結果如何，都會真的燒掉這張 Code。
3. 鎖 Account，比對「目前版本」與「核發版本」是否相同；不同就拒絕，即使 Code 本身還沒過期。
4. 重新執行 `AccountLoginPolicy`（擋帳號被停用）。

這堵住了「核發後、兌換前發生 UNLINK／改密碼／全裝置登出」的窗口——沒有這個快照比對，一次已經被使用者自己撤銷過的 Google 登入事件，仍可能在事後補簽出一張帶最新版本的 JWT。

## Feature flag 與啟動驗證

`app.oauth.google.enabled=${GOOGLE_OAUTH_ENABLED:false}`。停用時：LOGIN 入口（`GoogleOAuthAuthorizationRequestResolver`）與 LINK 發起（`GoogleOAuthLinkInitiationService.initiate()`）都在**最前面**直接中止，不查/鎖 Account、不占用限流額度，對外一律回 404，不區分「功能沒開」與其他原因。

`GoogleOAuthStartupValidator`（`CommandLineRunner`，`@Order(-1)`）只在 `enabled=true` 時檢查：Client ID/Secret 不是 placeholder、四個前端 URL 是合法 http/https 且有實際主機名稱（拒絕 `https:foo` 這種語法合法但沒有 authority 的 opaque URI）、非本機主機必須 HTTPS、三組 TTL 都大於零。任一項不符就讓啟動失敗，不允許「宣告啟用但設定有誤」悄悄跑起來。

## Rate limiting

- `GET /oauth2/authorization/google`（LOGIN 發起）：per-IP，20/10 分 + 60/小時。
- `POST /api/account/me/oauth/google/link`（LINK 發起）：兩組獨立限流——`oauth-link-reauth`（密碼猜測，答對即清除）與 `oauth-link-initiate`（Account ID 鍵值，5/10 分 + 15/小時，**答對密碼也不清除**，防止已知密碼的人無限重複發起）。
- `POST /api/account/me/set-password`（OAuth-only 設定第一組本地密碼）：**未設獨立限流**——這支不比對任何舊密碼，沒有密碼猜測面；取得 Account 悲觀鎖後先檢查 `passwordHash != null` 即拒絕，只有首次成功才執行一次 BCrypt 編碼。
- `prompt=select_account`：只加在 LINK 的 authorization request（多 Google 帳號時強制顯示帳號選擇器，避免綁到非預期身分）。LOGIN 刻意不加——那會改變全站 Google 登入體驗，屬另行決定。
- Callback 本身不做 IP 限流（來源 IP 可能合法地跟發起時不同），單次使用的 state 本身已是防重放機制。
- `X-Forwarded-For` 一律不信任（沒有設定受信任反向代理）。

## 清理排程

`AuthenticationCleanupScheduler`（原 `PasswordCleanupScheduler`，因職責擴大而改名）：`Clock` 注入、四個 `@Scheduled` cron 方法（錯開時間避免同時觸發），涵蓋 `PasswordResetToken`／`OAuthTransaction`／`OAuthRegistrationTicket`／`OAuthExchangeCode`，統一 7 天死亡後保留期。

## 已知限制（v1 刻意不做）

- 單機部署：`AuthorizationRequestRepository` 用 Spring 預設的 `HttpSession` 版本，多機部署需要 sticky session 或改用共用/持久化的 repository。
- 單機記憶體限流器：重啟即重置，多機會各自累計，尚未接 Redis／API Gateway 層限流。
- 多分頁併發 OAuth 流程：新流程會使舊分頁的流程失效（`FLOW_REPLACED`），不支援真正的多分頁並行。
- 前端整合細節（`authorizationUrl` 是相對路徑、DELETE 端點帶 request body 等）不在這份文件範圍——後端只保證 API 契約本身正確，前端串接需求另外處理。
- **OAuth-only 設定第一組本地密碼採「最小方案」**：只要求有效 JWT，不強制先重新走一次 Google 驗證。已知殘留風險：若 OAuth-only 使用者的 JWT 被竊，攻擊者可替帳號設定本地密碼、取得持久登入方式，之後甚至能解除 Google。緩解仍靠短 token TTL 與全裝置登出。**後續安全強化候選**：新增 `OAuthTransactionPurpose.SET_PASSWORD` 一次性證明，設定前強制 Google step-up 確認仍是原綁定的 `sub`——需擴充 Resolver／Handler／transaction 表，第五批範圍外，未實作。
- 密碼／OAuth 安全操作的通知信（設定密碼、解除綁定寄信提醒）尚未實作。
