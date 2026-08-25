# API Reference

> 主體對應 commit `9b2ab38`（2026-08-17），以原始碼為準逐一核對 controller、DTO、SecurityConfig 與 GlobalExceptionHandler。
> **2026-08-24 補上密碼管理章節（[2.10](#210-passwordcontrollerpasswordresetcontroller2026-08-24-補先前遺漏)）與登入限流說明**——這兩者當時已存在於程式碼但文件遺漏，僅補這兩處，未對全文重新逐支核對（OAuth 章節仍未收錄，屬另一批次）。
>
> 標記說明：🔓 免登入｜🔒 需登入｜👤 MEMBER｜🧑‍💼 EMPLOYEE｜🛡️ ADMIN｜🏋️ TRAINER
>
> 全文共 28 支 controller、約 108 個端點，另有 2 條 WebSocket 訊息目的地（此計數未包含本次新補的章節與後續各批次新增的 controller，僅供歷史參考）。

---

## 0. 快速索引

| 分類 | 前綴 | 章節 |
|---|---|---|
| 認證登入 | `/api/auth` | [2.1](#21-authcontroller) |
| 帳號驗證 | `/api/accounts` | [2.2](#22-accountverificationcontroller) |
| 會員註冊 | `/api/members/register` | [2.3](#23-memberregistercontroller) |
| 員工註冊／審核 | `/api/employees` | [2.4](#24-employeeregistercontroller)、[2.5](#25-employeereviewcontroller) |
| 會員資料（自助） | `/api/member/profile` | [2.6](#26-memberprofilecontrollerapimemberprofile) |
| 會員管理（後台） | `/api/admin/members` | [2.7](#27-adminmembercontrollerapiadminmembers-admin) |
| 員工資料（自助） | `/api/employee/profile` | [2.8](#28-employeeprofilecontrollerapiemployeeprofile) |
| 員工管理（後台） | `/api/admin/employees` | [2.9](#29-adminemployeecontrollerapiadminemployees-admin) |
| 密碼管理 | `/api/account/me/change-password`、`/api/auth/forgot-password`、`/api/auth/reset-password` | [2.10](#210-passwordcontrollerpasswordresetcontroller2026-08-24-補先前遺漏) |
| 商品／分類 | `/api/products`、`/api/product-categories` | [3](#3-商品) |
| 購物車 | `/api/cart` | [4](#4-購物車) |
| 訂單 | `/api/orders`、`/api/admin/orders` | [5](#5-訂單) |
| 金流 | `/api/payments` | [6](#6-金流ecpay) |
| 訂閱 | `/api/subscription-plans`、`/api/me/subscription` | [7](#7-訂閱) |
| 教練時段／預約 | `/api/trainer`、`/api/member` | [8](#8-私教教練時段與預約) |
| 教練檔案 | `/api/trainers`、`/api/employees/{id}/trainer`、`/api/trainer/profile`、`/api/admin/trainers` | [8.7](#87-教練檔案trainercontroller) |
| 私教課下架／重新上架 | `/api/admin/private-courses` | [8.10](#810-admin-私教課下架重新上架adminprivatecoursecontroller) |
| 團課 | `/api/member/group-classes`、`/api/admin/group-classes` | [9](#9-團課) |
| 課程分類／團課主檔 | `/api/course-categories`、`/api/admin/group-courses` | [10](#10-課程分類)、[10.1](#101-團課主檔apiadmingroup-courses-admin) |
| 優惠券 | `/api/coupons`、`/api/employee/coupons` | [11](#11-優惠券) |
| 營運報表 | `/api/admin/reports` | [12](#12-營運報表) |
| AI 健康（InBody／課表／聊天） | `/api/inbody`、`/api/ai-plans`、`/api/chat` | [13](#13-ai-健康) |
| WebSocket 聊天室 | `/ws-chat` | [14](#14-websocket-聊天室) |

---

## 1. 共通規範

### 1.1 基本資訊
- Base URL 預設 `http://localhost:8080`
- 請求／回應皆為 `application/json; charset=UTF-8`（少數端點例外，見各章節標註）
- 伺服器端產生的時間一律使用 `Asia/Taipei`

### 1.2 CORS
`CorsConfig`（dev／非 dev 共用）：

| 項目 | 值 |
|---|---|
| 允許來源 | `app.cors.allowed-origins`，預設 `http://localhost:5173`、`http://localhost:3000` |
| 允許方法 | GET, POST, PUT, PATCH, DELETE, OPTIONS |
| 允許 header | `*` |
| allowCredentials | `true` |

### 1.3 認證
- 機制：JWT Bearer token，請求帶 `Authorization: Bearer <token>`
- 取得：`POST /api/auth/login`
- token `subject` = **account id**（不是 member_id／employee_id，使用端須以 account_id 反查各身分表）
- 自訂 claim：`email`
- 有效期 `app.jwt.access-token-ttl`，預設 `PT2H`（2 小時）；issuer 預設 `ispan-gym`
- **沒有 refresh token 機制**，過期後需重新登入
- Session 為 STATELESS，CSRF 關閉

### 1.4 角色
`AccountAuthorityResolver` 依帳號當下狀態展開，未啟用的身分不授予任何權限，權限彼此不互斥：

| 角色 | 授予條件 |
|---|---|
| `MEMBER` | 帳號含 MEMBER 身分，且 Member 未刪除、status = ACTIVE |
| `EMPLOYEE` | 帳號含 EMPLOYEE 身分，且 Employee 未刪除、status = ACTIVE（所有在職員工的基底角色） |
| `ADMIN` | 符合 EMPLOYEE 條件，且 `employee.role = ADMIN` |
| `TRAINER` | 符合 EMPLOYEE 條件，且有對應且未刪除的 Trainer 資料列（只看 `deletedAt`，不看 `Trainer.status`） |

登入回應的 `roles` 是去掉 `ROLE_` 前綴的清單，僅供前端導流；實際授權後端每請求檢查。

**角色是疊加的，不是互斥的。** `EMPLOYEE` 是所有在職員工的基底角色，`ADMIN` 與 `TRAINER` 都在其之上**額外加發**而非取代 —— 因為 `/api/employee/**`、`/api/employees/reviews/**` 這類端點只認 `EMPLOYEE`，若管理員不同時具備 `EMPLOYEE` 反而會被擋在門外。

實際會出現的組合：

| 情境 | `roles` |
|---|---|
| 一般會員 | `["MEMBER"]` |
| 一般員工（櫃檯、行政） | `["EMPLOYEE"]` |
| 管理員 | `["EMPLOYEE","ADMIN"]` |
| **教練** | `["EMPLOYEE","TRAINER"]` |
| 身兼管理職的教練 | `["EMPLOYEE","ADMIN","TRAINER"]` |
| 員工同時也是本館會員 | `["MEMBER","EMPLOYEE"]` |
| 沒有任何啟用身分 | `[]` |

> 注意 **TRAINER 不是 `AccountType` 或 `EmployeeRole` 的列舉值** —— 那兩個 enum 分別只有
> `{MEMBER, EMPLOYEE}` 與 `{EMPLOYEE, ADMIN}`。教練身分是看 `trainer` 表有沒有該員工的資料列，
> 存在即為教練。詳見內部設計文件 ER-MODEL（不進版控，向隊員索取）。

> 空陣列代表帳號存在但沒有可用身分。前端實務上不會收到它 —— `AuthController` 在這種情況
> 根本不簽發 token，而是回 401 並說明原因（見 [2.1](#21-authcontroller)）。

> 管理員**沒有** `MEMBER`。帶合法 ADMIN token 打 `/api/member/**` 會回 `403 ACCESS_DENIED`。
> 要同時具備兩種身分，必須是同一個 account 底下同時存在 member 與 employee 兩筆資料列。

前端判斷角色時**要用「包含」而非「等於」**，且不可依賴陣列順序：

```js
if (auth.roles.includes('TRAINER')) { ... }   // ✅
if (auth.roles === 'TRAINER') { ... }         // ❌ 教練的 roles 是 ["EMPLOYEE","TRAINER"]
if (auth.roles[0] === 'TRAINER') { ... }      // ❌ 順序不保證
```

### 1.5 路徑層級授權（SecurityConfig）

免登入清單（其餘一律 `authenticated()`）：

```
OPTIONS /**
/api/auth/**
POST    /api/members/register
POST    /api/employees/register
POST    /api/employees/reapply
GET     /api/accounts/verify-email
POST    /api/accounts/resend-verification-email
GET     /api/products/**
GET     /api/product-categories          （精確路徑，無 /**）
GET     /api/course-categories           （精確路徑，無 /**）
GET     /api/member/trainers/*/schedules
GET     /api/member/group-classes        （精確路徑，無 /**）
GET     /api/trainers、/api/trainers/*   （公開教練介紹）
GET     /api/subscription-plans
POST    /api/payments/ecpay/notify
/oauth2/**、/login/oauth2/**、/ws-chat/**、/error
```

角色限定：

| 路徑 | 要求 |
|---|---|
| `/api/admin/**` | `hasRole("ADMIN")` |
| `/api/employee/**` | `hasRole("EMPLOYEE")` |
| `/api/employees/reviews/**` | `hasRole("EMPLOYEE")`（任何在職員工皆可審核，刻意獨立於 `/api/admin/**` 之外） |
| `POST /api/employees/*/trainer` | `hasRole("ADMIN")`（員工升級教練限管理者，與員工審核是不同動作的不同權限） |

> `/api/member/**` 刻意沒有整段放行 —— 同前綴下還有 `GET /api/member/bookings` 等個人資料端點，
> 一併放行會使其變成匿名可讀。放行的是逐條精確列出的公開排班資訊。

### 1.6 錯誤格式
所有錯誤（含 filter 層的 401／403）統一回 `ApiErrorResponse`：

```json
{
  "code": "PRODUCT_NOT_FOUND",
  "message": "找不到商品：12",
  "timestamp": "2026-08-17T09:30:00"
}
```

欄位驗證失敗（`INVALID_REQUEST`）另帶選填的 `errors`，且 `message` 是**該條規則自己的訊息**而非統一字串：

```json
{
  "code": "INVALID_REQUEST",
  "message": "開始時間必須是未來時間",
  "timestamp": "2026-08-18T09:30:00",
  "errors": {
    "startAt": "開始時間必須是未來時間",
    "endAt": "結束時間必須是未來時間"
  }
}
```

- `message` 取第一則，前端可直接顯示；`errors` 供表單逐欄標紅
- 同一欄位多條規則同時失敗只保留第一則，避免訊息洗版
- 跨欄位規則（`@AssertTrue`）的 key 是**判斷方法的屬性名**（如 `durationValid`），對不上實際輸入欄位，因此以 `message` 為主
- 無欄位錯誤時不帶 `errors`，`message` 退回「請確認輸入資料是否正確」

**通用錯誤碼**

| code | HTTP | 觸發時機 |
|---|---|---|
| `INVALID_REQUEST` | 400 | `@Valid` 驗證失敗／body 缺失或 JSON 格式錯／缺必要參數／參數型別轉換失敗 |
| `UNAUTHENTICATED` | 401 | 未登入、token 無效或過期、帳號無可用身分 |
| `ACCESS_DENIED` | 403 | 角色不符 |
| `NOT_FOUND` | 404 | 找不到資源，或路徑對應不到任何 API |
| `METHOD_NOT_ALLOWED` | 405 | 不支援的 HTTP 方法 |
| `CONCURRENT_UPDATE_CONFLICT` | 409 | 樂觀鎖版本衝突（`@Version`） |
| `CONFLICT` | 409 | 資料庫 constraint 違反 |
| `SERVICE_TIMEOUT` | 503 | async 端點逾時（`spring.mvc.async.request-timeout=130000`） |
| `INTERNAL_ERROR` | 500 | 未預期例外（細節只寫 log，不外洩） |

> `@Valid` 失敗一律回固定訊息「請確認輸入資料是否正確」，**不回傳欄位層級的錯誤明細**，前端需自行做前端驗證提示。

**業務錯誤碼**

| code | HTTP | 意義 |
|---|---|---|
| `BUSINESS_RULE_VIOLATION` | 409 | 通用業務規則違反 |
| `CONFIRMATION_REQUIRED` | 409 | 需二次確認，補參數後重送（見 [8.6](#86-兩種二次確認流程)） |
| `PRODUCT_NOT_FOUND` | 404 | 找不到商品 |
| `PRODUCT_UNAVAILABLE` | 400 | 商品非 ACTIVE，無法購買 |
| `INSUFFICIENT_STOCK` | 409 | 庫存不足 |
| `INVALID_QUANTITY` | 400 | 數量非正整數 |
| `CART_ITEM_NOT_FOUND` | 404 | 找不到購物車項目 |
| `CART_ITEM_TYPE_NOT_ALLOWED` | 400 | 購物車不接受此品項類型 |
| `FORBIDDEN_CART_ACCESS` | 403 | 存取他人購物車項目 |
| `DIRECT_CHECKOUT_NOT_ALLOWED` | 400 | 此品項不可直接結帳 |
| `SUBSCRIPTION_REQUIRED` | 403 | 需先訂閱才能購買私教課 |
| `FORBIDDEN_ACCESS` | 403 | 無權存取此資源 |
| `AI_HEALTH_PLAN_NOT_FOUND` | 404 | 找不到 AI 課表 |
| `FORBIDDEN_AI_HEALTH_PLAN_ACCESS` | 403 | 無權存取此課表 |
| `INBODY_RECORD_NOT_FOUND` | 404 | 找不到 InBody 紀錄 |
| `FORBIDDEN_INBODY_ACCESS` | 403 | 無權存取此 InBody 紀錄 |
| `DUPLICATE_INBODY_RECORD` | 409 | 同日已有 InBody 紀錄 |
| `MISSING_INBODY_MEASUREMENT` | 400 | 未填任何量測值 |
| `INVALID_GOAL` | 400 | 不支援的訓練目標 |
| `AI_SERVICE_UNAVAILABLE` | 503 | Gemini 服務異常 |
| `PDF_GENERATION_FAILED` | 500 | PDF 產生失敗 |

### 1.7 ⚠️ dev profile 全放行

`application.properties` 目前寫死 `spring.profiles.active=dev`，此時：

- `DevSecurityConfig` 以 `anyRequest().permitAll()` 取代 `SecurityConfig`，**本文件所有授權規則一律不生效**，`@PreAuthorize` 也不生效
- 登入身分固定為 `app.dev-current-user-id`（預設 1），非真實登入者
- **例外：WebSocket 的 STOMP CONNECT 驗證在所有 profile 都生效**，本機測試聊天室仍須先登入取得 token

要驗證真實權限：設環境變數 `SPRING_PROFILES_ACTIVE=<非 dev>`，或註解掉 `application.properties` 的該行。
對外部署另需提供 `JWT_SECRET`／`DB_PASSWORD`／`SEED_ADMIN_*` 等環境變數（現行預設值已進版控，等同公開），並關閉 `ddl-auto`。

---

## 2. 帳號與註冊

### 2.1 AuthController

#### 🔓 `POST /api/auth/login`

Request `LoginRequest`：

| 欄位 | 型別 | 驗證 |
|---|---|---|
| `email` | String | `@NotBlank` `@Email` |
| `password` | String | `@NotBlank` |

Response `200` `LoginResponse`：

| 欄位 | 型別 | 說明 |
|---|---|---|
| `accessToken` | String | JWT |
| `accountId` | Integer | account 主鍵 |
| `email` | String | |
| `roles` | List\<String\> | 疊加式角色清單，如 `["MEMBER"]`、`["EMPLOYEE","ADMIN"]`、教練為 `["EMPLOYEE","TRAINER"]`；完整組合見 [1.4](#14-角色) |

錯誤（皆 `401 UNAUTHENTICATED`）：

| 情況 | message |
|---|---|
| 查無 email／帳號已軟刪／密碼錯 | 帳號或密碼錯誤 |
| 無 ACTIVE 身分，員工為 PENDING_APPROVAL | 員工帳號尚待審核，請等候通知。 |
| 無 ACTIVE 身分，員工為 REJECTED | 員工申請未通過，如需重新申請請使用重新申請功能。 |
| 其他（含信箱未驗證的會員） | 此帳號目前沒有可用的身分，請聯繫管理員。 |

> 密碼正確但無任何可用身分時**不簽發 token**，改回具體狀態訊息，避免前端拿到實際上什麼都做不了的 JWT。

**登入限流（2026-08-24 補）**：`429 LOGIN_RATE_LIMITED`「登入嘗試次數過多，請稍後再試」。兩層防護：
IP 每分鐘 10 次／每小時 30 次（次要、較粗略的防線，先檢查，避免有心人用大量不存在的
email 從同一 IP 灌爆記憶體）；正規化後的 email 15 分鐘內 5 次（主要防線，與修改密碼的
猜測防護門檻一致）。密碼答對的當下即清空該 email 的計數，不論最後是否真的核發 token。
一律回同一句訊息，不區分是 IP 還是 email 觸發、也不區分帳號是否存在。

### 2.2 AccountVerificationController

#### 🔓 `GET /api/accounts/verify-email?token={UUID}`

Query：`token` UUID，必填。
Response `200` `VerifyEmailResponse`：`purpose` String（`REGISTRATION` | `REACTIVATION`）、`email` String（2026-08-25 補，供前端跨分頁通知比對用；能持有寄到該信箱的一次性 token 即已證明是本人，回傳信箱本身不構成新的帳號列舉風險）

- 已驗證過的 token 再打 → **200 冪等成功**，回原本的 purpose
- `REGISTRATION` 成功：會員 `PENDING` → `ACTIVE`
- `REACTIVATION` 成功：還原軟刪的 member／account，補回 MEMBER 角色

錯誤：`404` 驗證連結無效／找不到對應的會員資料；`409` 此驗證連結不適用於會員帳號／驗證連結已過期，請重新註冊或申請重寄驗證信／此帳號已被停權，請聯繫客服／此驗證連結已失效

#### 🔓 `POST /api/accounts/resend-verification-email`

Request `ResendVerificationRequest`：`email`（`@NotBlank` `@Email`）
Response **`202 ACCEPTED`** `ResendVerificationResponse`：`message` 固定為「如果這個 Email 尚待驗證，新的驗證信將會寄出。」

> **反帳號列舉設計**：帳號不存在、已 ACTIVE、已停權、或仍在重寄冷卻時間內，一律靜默回相同訊息，不透露帳號是否存在。只有 email 格式錯誤才會看到 400。

#### 🔓 `POST /api/accounts/check-email-availability`（2026-08-24 補，先前遺漏）

註冊表單即時可用性檢查，讓前端能在使用者填完 Email、還沒填完整份表單前就先提示。

Request `CheckEmailAvailabilityRequest`：`email`（`@NotBlank` `@Email`）
Response `200` `CheckEmailAvailabilityResponse`：`available` boolean

`available` 的判斷跟核心帳號狀態分支與 `POST /api/members/register` 一致，但**不包含**
註冊本身會用到的 60 秒重寄冷卻：帳號不存在、或帳號存在但屬於 PENDING 未驗證／已軟刪除
（這兩種送出表單會成功，分別觸發重寄驗證信／重新啟用信，不會報錯）→ `true`；帳號已被
其他身分占用、已是完整 ACTIVE 會員、或已被停權 → `false`。極少數情況即時檢查顯示可用、
但真正送出時剛好卡在冷卻窗口內仍可能回 409，真正的權威判斷永遠是
`POST /api/members/register` 本身。

> **刻意接受的取捨**：這支端點本質上是帳號列舉管道（可用來probe任意 Email 是否已註冊），
> 用限流降低風險而非假裝問題不存在——同 IP 每分鐘 10 次、每小時 30 次（與登入 IP 層一致）。
> 超過限流回 `429 EMAIL_CHECK_RATE_LIMITED`「檢查次數過多，請稍後再試」。

錯誤：`400 INVALID_REQUEST` email 格式錯誤；`429 EMAIL_CHECK_RATE_LIMITED` 見上。

### 2.3 MemberRegisterController

#### 🔓 `POST /api/members/register` → `201 CREATED`

Request `MemberRegisterRequest`：

| 欄位 | 型別 | 驗證 |
|---|---|---|
| `firstName` | String | `@NotBlank` `@Size(max=50)` |
| `lastName` | String | `@NotBlank` `@Size(max=50)` |
| `email` | String | `@NotBlank` `@Email` `@Size(max=100)` |
| `phone` | String | `@NotBlank` `@Size(max=10)` `@Pattern(^09\d{8}$)` |
| `password` | String | `@NotBlank` `@Pattern` — 8~72 字元，須含大小寫英文與數字，不可有空白 |
| `dob` | LocalDate | `@Past`，選填 |
| `gender` | Gender | `M`／`F`／`OTHER`，選填 |

Response `MemberRegisterResponse`：`email`（正規化後）、`message`。刻意不回傳 id／姓名／狀態，避免匿名端點變成帳號列舉管道。

`message` 三種情境：

| 情境 | message |
|---|---|
| 全新註冊 | 註冊成功，請查收驗證信完成帳號啟用。 |
| 帳號曾軟刪（寄重新啟用信，不動密碼與個資） | 此帳號已停用，我們已寄出重新啟用的驗證信，請查收。 |
| 帳號存在但尚未驗證（重寄驗證信） | 此帳號尚待驗證，我們已重新寄出驗證信，請查收。 |

錯誤 `409`：此 Email 已有帳號，請改用登入。／此 Email 已被註冊／請稍後再試，前一封驗證信仍在有效範圍內

> 寄信失敗不會讓請求失敗，只記 log。

### 2.4 EmployeeRegisterController

#### 🔓 `POST /api/employees/register` → `201 CREATED`

Request `EmployeeRegisterRequest`：`firstName`、`lastName`（`@NotBlank` `@Size(max=50)`）、`email`（`@NotBlank` `@Email` `@Size(max=100)`）、`phone`（`@NotBlank` `@Size(max=20)`，**無格式限制**）、`password`（同會員規則）、`hireDate`（`@NotNull`）
Response `EmployeeRegisterResponse`：`email`、`message`（註冊成功，請等候審核您的帳號。）

建立時 `role` 寫死為 `EMPLOYEE`（申請者無法自選 ADMIN）、`status = PENDING_APPROVAL`。
**員工沒有 email 驗證信流程**，只靠人工審核啟用。

錯誤 `409`：此 Email 已有帳號，請改用登入或聯繫管理員。

#### 🔓 `POST /api/employees/reapply` → `200`（注意不是 201）

供 `REJECTED` 的申請者重新送件。申請者沒有 JWT，改以 email + 密碼自行驗證身分，**不簽發 token、不變更密碼**。

Request `EmployeeReapplyRequest`：`email`、`password`（身分證明）、`firstName`、`lastName`、`phone`、`hireDate`
Response `EmployeeRegisterResponse`：`message`（已重新送出申請，請等候審核。）

成功時覆寫姓名／電話／到職日，狀態轉回 `PENDING_APPROVAL`，並清空 `reviewedBy`／`reviewedAt`／`reviewNote`。

錯誤 `409`（**注意：憑證錯誤在此回 409，不是 401**，因為端點自行驗密碼，不走 Spring Security）：
Email 或密碼錯誤／此帳號沒有可重新申請的員工紀錄／此員工申請目前不是可重新申請的狀態

### 2.5 EmployeeReviewController

前綴 `/api/employees/reviews`，全部 🧑‍💼 **EMPLOYEE**（filter 層 + `@PreAuthorize` 雙重把關）。任何在職員工皆可審核，不限管理者。

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/pending` | 待審清單 |
| POST | `/{employeeId}/approve` | 核准 → `200` 空 body |
| POST | `/{employeeId}/reject` | 婉拒 → `200` 空 body |

`GET /pending` → `List<PendingEmployeeResponse>`：`employeeId`、`firstName`、`lastName`、`email`、`phone`、`role`、`hireDate`、`createdAt`。只列 `PENDING_APPROVAL` 且未刪除者。

`approve` Request `EmployeeApproveRequest`：`reviewNote` `@Size(max=500)` 選填（**body 仍為必送，至少傳 `{}`**）
`reject` Request `EmployeeRejectRequest`：`reason` `@NotBlank` `@Size(max=500)` **必填**，存入 `reviewNote`

> 審核者身分取自登入 token（`CurrentUserProvider`），不由前端傳入。

錯誤：`404` 找不到此員工申請／找不到審核者的員工資料；`409` 此員工申請已被刪除／此員工帳號目前不是待審核狀態

---

### 2.6 MemberProfileController（`/api/member/profile`）

| 方法 | 路徑 | 權限 | 成功狀態 | 說明 |
|---|---|---|---|---|
| GET | `/api/member/profile` | 🔒 需登入 | 200 | 查自己的會員資料 |
| PUT | `/api/member/profile` | 👤 MEMBER | 200 | 修改自己的會員資料 |

> GET 沒有方法層級 `@PreAuthorize`，只要求已登入；能不能取到 Member 交由 Service 判斷
> （帳號可能只有員工身分而無會員資料 → `403`）。PUT 另加 `hasRole('MEMBER')`。

`MemberProfileUpdateRequest`：
`firstName`／`lastName`（`@NotBlank`，≤50）、`phone`（`@NotBlank`，≤10，`^09\d{8}$`）、
`dob`、`gender`、`emergencyContact`（≤50）、`emergencyPhone`、`zipCode`、`city`、`district`、`address`

`MemberProfileResponse`：上述欄位 ＋ `memberId`、`email`（來自 account）、`status`

主要錯誤：`403 FORBIDDEN_ACCESS` 此帳號沒有可用的會員資料（無 Member、已軟刪除，或 PUT 時非 `ACTIVE`）

> **實作重點**：Service 只接受 `accountId`，不接受 `memberId`。
> 修改走 `findByAccountIdForUpdate()` 悲觀鎖，且刻意是該 transaction 內**第一次**載入 Member——
> 若先經 `requireCurrentMemberId()`（內部會未鎖定載入一次），Persistence Context 會快取該實體，
> 之後即使鎖定查詢命中同一列，Hibernate 仍回傳先前那個舊物件，鎖形同白鎖。

---

### 2.7 AdminMemberController（`/api/admin/members`，🛡️ ADMIN）

| 方法 | 路徑 | 成功狀態 | 說明 |
|---|---|---|---|
| GET | `/api/admin/members` | 200 | 會員列表（分頁） |
| GET | `/api/admin/members/{memberId}` | 200 | 單筆詳細 |
| PUT | `/api/admin/members/{memberId}` | 200 | 修改會員資料 |
| POST | `/api/admin/members/{memberId}/suspend` | 200 | 停權 |
| POST | `/api/admin/members/{memberId}/activate` | 200 | 復權 |

**列表查詢參數**：`keyword`（不分大小寫比對姓名／手機／登入 email）、`status`（不傳＝全部）、
`page`（預設 0）、`size`（預設 20，**上限 100**）

> 列表以 `JOIN FETCH` 帶出 account，避免映射時逐筆 lazy load 造成 N+1；一律排除軟刪除的會員。

**狀態轉換規則**：

| 動作 | 目前狀態 | 結果 |
|---|---|---|
| suspend | `ACTIVE` | → `SUSPENDED` |
| suspend | `SUSPENDED` | `409` 此會員已是停權狀態 |
| suspend | `PENDING` | `409` 此會員尚未完成驗證，無法停用 |
| activate | `SUSPENDED` | → `ACTIVE` |
| activate | `ACTIVE` | `409` 此會員已是啟用狀態 |
| activate | `PENDING` | `409` 尚未完成驗證，請由會員完成 Email 驗證 |

> `PENDING` 兩個方向都擋：該狀態要靠會員自己點驗證信轉為 `ACTIVE`，管理者不代為跳過。

主要錯誤：`400 INVALID_REQUEST` 頁碼不可小於 0／每頁筆數必須介於 1 到 100；`404` 查無此會員

---

### 2.8 EmployeeProfileController（`/api/employee/profile`）

不動既有的員工申請（2.4）、同儕審核（2.5）、拒絕後重新申請流程。

| 方法 | 路徑 | 權限 | 成功狀態 | 說明 |
|---|---|---|---|---|
| GET | `/api/employee/profile` | 🧑‍💼 EMPLOYEE | 200 | 查自己的員工資料 |
| PUT | `/api/employee/profile` | 🧑‍💼 EMPLOYEE | 200 | 修改自己的電話 |

> GET／PUT 都掛 `hasRole('EMPLOYEE')`——跟 `MemberProfileController` 的 GET 只要求已登入不同，
> 是刻意的：若只要求已登入，同一帳號若還有其他啟用中身分（例如同時是 `ACTIVE` 會員），
> `INACTIVE` 的員工身分仍會被看到並讀出資料。用 `hasRole('EMPLOYEE')` 才能把員工身分本身
> 是否 `ACTIVE` 的判斷跟同帳號其他身分脫鉤——`INACTIVE` 員工完全看不到、也改不了自己的資料。

`EmployeeProfileUpdateRequest`：`phone`（`@NotBlank`，≤20，無格式限制，比照 `EmployeeRegisterRequest`）

> 姓名屬正式人事資料，本人不可自行修改，只能由 ADMIN 透過 2.9 代改。

`EmployeeProfileResponse`：`employeeId`、`email`（來自 account）、`firstName`、`lastName`、`phone`、
`role`、`status`、`hireDate`、`isTrainer`、`createdAt`（無 `updatedAt`，比照 `MemberProfileResponse`）

主要錯誤：`403 FORBIDDEN_ACCESS` 此帳號沒有可用的員工資料（無 Employee、已軟刪除，或非 `ACTIVE`）

> **實作重點**：GET 用 `requireCurrentEmployeeId()`（`hasRole('EMPLOYEE')` 已在安全層保證
> `ACTIVE`，GET 沒有後續鎖定步驟）；PUT 用 `requireCurrentUserId()`（只回傳 accountId，不在
> Controller 層載入 Employee entity），Service 內以 `findByAccountIdForUpdate()` 一次鎖定——
> 理由同 `MemberProfileController`，避免 identity map 讓鎖形同白鎖。

---

### 2.9 AdminEmployeeController（`/api/admin/employees`，🛡️ ADMIN）

| 方法 | 路徑 | 成功狀態 | 說明 |
|---|---|---|---|
| GET | `/api/admin/employees` | 200 | 員工列表（分頁） |
| GET | `/api/admin/employees/{employeeId}` | 200 | 單筆詳細 |
| PUT | `/api/admin/employees/{employeeId}` | 200 | 代改姓名／電話／到職日 |
| POST | `/api/admin/employees/{employeeId}/role` | 200 | 角色調整（`EMPLOYEE`⇄`ADMIN`） |
| POST | `/api/admin/employees/{employeeId}/suspend` | 200 | 停用 |
| POST | `/api/admin/employees/{employeeId}/activate` | 200 | 恢復 |

**列表查詢參數**：`keyword`（不分大小寫比對姓名／手機／登入 email）、`status`、`role`（皆不傳＝全部）、
`page`（預設 0）、`size`（預設 10，**上限 100**）、`sort`（`id`預設／`name`／`hireDate`／`createdAt`，
白名單外靜默回退 `id`）、`direction`（`asc`／`desc`，僅 `desc` 生效，其餘回退 `asc`）

> 列表以 `JOIN FETCH` 帶出 account，避免映射時逐筆 lazy load 造成 N+1；一律排除軟刪除的員工。
> 排序皆附加 `id ASC` 當穩定 tiebreaker（主排序本身就是 `id` 時例外，不重複附加）。

`AdminEmployeeUpdateRequest`：`firstName`／`lastName`（`@NotBlank`，≤50）、`phone`（`@NotBlank`，≤20）、
`hireDate`（`@NotNull`）——PUT 為完整覆蓋語意，四欄皆必填，不含 `role`／`status`。

`AdminEmployeeRoleUpdateRequest`：`role`（`@NotNull`，`EmployeeRole`）

> **Trainer 姓名快照同步（2026-08-19 補丁）**：PUT 修改姓名時，若該員工有 Trainer 資料列
> （不論 `ACTIVE`／`UNAVAILABLE`／已軟刪除），同一交易內一併同步 `Trainer.firstName`／`lastName`。
> 這兩欄是 `Trainer` 建立時自 Employee 複製的快照（`SchemaFixupRunner.addTrainerProfileColumns()`）。
> `SchemaFixupRunner` 確實有 `fixReversedTestAccountNames()` 會在每次應用**啟動**時把所有不同步的
> Trainer 姓名重新對齊 Employee，但只在重啟當下跑一次；正式修改（本端點）到下次部署或重啟之間，
> 兩邊姓名仍會不一致，因此這裡的即時同步仍必要，不是被那個機制取代。
> 不同步 `Trainer.email`——Account email 修改不在本階段範圍內。

**狀態轉換規則**：

| 動作 | 目前狀態 | 結果 |
|---|---|---|
| suspend | `ACTIVE`（無有效 Trainer、非最後一位有效 ADMIN） | → `INACTIVE` |
| suspend | `ACTIVE` 但具有效 Trainer 身分 | `409` 此員工仍具有效教練身分，須待 Trainer 管理階段釋出教練身分後才能停用 |
| suspend | `ACTIVE` 但是最後一位有效 ADMIN | `409` 必須至少保留一位有效管理員，無法停用最後一位 |
| suspend | `INACTIVE` | `409` 此員工已是停用狀態 |
| suspend | `PENDING_APPROVAL` | `409` 此員工尚未通過審核，無法停用 |
| suspend | `REJECTED` | `409` 此員工申請已被拒絕，無法停用 |
| activate | `INACTIVE` | → `ACTIVE` |
| activate | `ACTIVE` | `409` 此員工已是啟用狀態 |
| activate | `PENDING_APPROVAL` | `409` 此員工尚未通過審核，請由審核流程處理 |
| activate | `REJECTED` | `409` 此員工申請已被拒絕，請走重新申請流程 |
| role→相同角色 | 任何狀態 | `409` 此員工已經是指定角色 |
| role→任何值 | 非 `ACTIVE` 或已軟刪除 | `409` 此員工目前不是在職狀態，無法調整角色 |
| role→`EMPLOYEE`（降級） | `ACTIVE` `ADMIN`，是最後一位有效 ADMIN | `409` 必須至少保留一位有效管理員，無法降級最後一位 |

> **有效 Trainer**＝`trainer.deletedAt IS NULL`（`ACTIVE`／`UNAVAILABLE` 皆算），已軟刪除不算。
> **有效 ADMIN**＝`role=ADMIN && status=ACTIVE && deletedAt=null`。判定與寫入皆在悲觀鎖下完成：
> 會減少有效 ADMIN 數量的操作（降級、停用）一律先鎖定整組有效 ADMIN（依 `employeeId ASC`），
> 避免兩個併發請求各自用鎖定前讀到的數量通過檢查、一起把系統的 ADMIN 降到零。
> **允許 ADMIN 對自己執行降級／停用**，只要通過上述數量檢查——不額外禁止自我操作。
> 「第一位 ADMIN 怎麼生」由既有的 `AdminSeedRunner`（預設關閉）處理，這裡的角色調整
> 端點是後續新增 ADMIN 的一般路徑。

主要錯誤：`400 INVALID_REQUEST` 頁碼不可小於 0／每頁筆數必須介於 1 到 100；`404` 查無此員工（含已軟刪除）；`409` 見上表

---

### 2.10 PasswordController／PasswordResetController（2026-08-24 補，先前遺漏）

密碼是 `Account` 層級的概念，不分會員／員工共用同一套。`account.passwordHash == null`
（純 OAuth 帳號）視為沒有本地密碼可用，見下方各端點各自的處理方式。

#### 🔒 `POST /api/account/me/change-password`

已登入即可用（不需特定角色），`accountId` 一律取自 JWT，request body 不接受該欄位。

Request `ChangePasswordRequest`：`currentPassword`（`@NotBlank`）、`newPassword`（`@NotBlank`，見下方密碼規則）、`confirmPassword`（`@NotBlank`，須與 `newPassword` 相同）

Response `200` `ChangePasswordResponse`：`accessToken`／`accountId`／`email`／`roles`——**同一回應內附一組新 JWT**，前端原地替換掉舊 token 即維持登入；舊 token（含其他裝置）立即失效（`credentialsVersion` 遞增）。

錯誤：`409 PASSWORD_NOT_CONFIGURED` 純 OAuth 帳號沒有本地密碼可改；`400 CURRENT_PASSWORD_INCORRECT` 目前密碼錯誤（計入下方限流）；`409 PASSWORD_REUSED` 新密碼與目前密碼或近 4 組歷史密碼重複；`429 PASSWORD_CHANGE_RATE_LIMITED` 同帳號 15 分鐘內目前密碼連續猜錯達 5 次。

#### 🔓 `POST /api/auth/forgot-password`

Request `ForgotPasswordRequest`：`email`（`@NotBlank` `@Email`）
Response **`202`** `ForgotPasswordResponse`：`message` 固定為「若此 Email 可使用密碼登入，我們將寄送密碼重設信。」

> **反帳號列舉**：不論帳號不存在、是純 OAuth 帳號、或已達限流上限，一律回相同的 `202` 與訊息。信件連結導向前端 `/reset-password?token=`，15 分鐘內有效，重寄冷卻 60 秒；新申請會讓同帳號先前未使用的舊連結失效。

#### 🔓 `POST /api/auth/reset-password`

Request `ResetPasswordRequest`：`token`（`@NotBlank`）、`newPassword`（`@NotBlank`，見下方密碼規則）、`confirmPassword`（`@NotBlank`，須與 `newPassword` 相同）
Response **`204`**（無 body，不核發 JWT——視為救援可能已外流的帳號，一律導回登入頁重新輸入密碼）

錯誤：`400 RESET_TOKEN_INVALID`「重設連結無效或已過期，請重新申請。」——涵蓋 token 不存在／已使用／已作廢／已過期／帳號已不符資格共 5 種情況，**刻意不區分**，避免回應形狀被拿來反推帳號狀態。

#### 密碼規則（`@ValidPassword`，適用 `newPassword`，不適用 `currentPassword`／登入密碼）

8～72 字元、須含大小寫英文字母與數字、不可包含任何空白字元，且 UTF-8 編碼後不得超過 72 bytes（BCrypt 以 byte 計算有效輸入長度，多位元字元湊到 72 字元時可能已超過 72 bytes 而被靜默截斷）。與會員／員工註冊的密碼規則完全相同，同一個密碼在任一個「建立密碼」端點都會有一致的驗證結果。

---

## 3. 商品

### 3.1 ProductController

#### 🔓 `GET /api/products`

Query 參數：

| 參數 | 型別 | 預設 | 說明 |
|---|---|---|---|
| `productCategoryId` | Integer | — | 分類篩選 |
| `keyword` | String | — | LIKE 比對 `name`／`description`／`brand` |
| `brand` | String | — | 精確比對 |
| `flavor` | String | — | 精確比對 |
| `productSize` | String | — | **商品尺寸**，精確比對 |
| `minPrice` | BigDecimal | — | 價格下限 |
| `maxPrice` | BigDecimal | — | 價格上限 |
| `page` | int | `0` | 頁碼，從 0 起算 |
| `size` | int | `10` | **每頁筆數** |

> ⚠️ 命名易混淆：`size` 是「每頁筆數」，商品尺寸的篩選參數叫 `productSize`。

排序固定為 `createdAt DESC`，**不接受 `sort` 參數**。只回傳 `status = ACTIVE` 的商品。

Response `200`：Spring Data `Page<ProductResponse>` 標準結構（`content`、`totalElements`、`totalPages`、`number`、`size`、`first`、`last` …）

#### 🔓 `GET /api/products/{productId}`
Response `ProductResponse`；錯誤 `404 PRODUCT_NOT_FOUND`。
> 此端點**不檢查狀態**，已下架（INACTIVE／ARCHIVED）商品仍可用 id 直接讀取。

#### 🛡️ `POST /api/admin/products/upload` → `200`
Request `@Valid ProductRequest`。雖然路徑有 `upload` 字樣，**實際上是純 JSON，不是 multipart 檔案上傳**。

#### 🛡️ `PUT /api/admin/products/{productId}` → `200`
Request `@Valid ProductRequest`。**全量覆寫**，未帶的選填欄位會被設為 null。

#### 🛡️ `DELETE /api/admin/products/{productId}` → `200`
軟刪除（`status` 改為 `INACTIVE`）。Response body 是**純字串** `商品已下架`，非 JSON。

**ProductRequest**

| 欄位 | 型別 | 驗證 |
|---|---|---|
| `name` | String | `@NotBlank` |
| `price` | BigDecimal | `@NotNull` `@DecimalMin("0.01")` |
| `stock` | Integer | `@NotNull` `@Min(0)` |
| `description`／`imageUrl`／`brand`／`flavor`／`size`／`color`／`material`／`origin` | String | — |
| `weight` | Double | — |
| `productCategoryId` | Integer | — |

`status` 不可由 request 設定。

**ProductResponse**：`productId`、`name`、`description`、`price`、`stock`、`status`（`ACTIVE`｜`INACTIVE`｜`ARCHIVED`）、`imageUrl`、`categoryId`、`categoryName`、`createdAt`、`brand`、`weight`、`flavor`、`size`、`color`、`material`、`origin`

> 不對稱：request 用 `productCategoryId`，response 用 `categoryId` + `categoryName`。

### 3.2 ProductCategoryController

| 方法 | 路徑 | 權限 |
|---|---|---|
| GET | `/api/product-categories` | 🔓 |
| POST | `/api/admin/product-categories` | 🛡️ |
| DELETE | `/api/admin/product-categories/{id}` | 🛡️ ⚠️ 見下方 |

`ProductCategoryDto`（request／response 共用，**無任何驗證註解**）：`productCategoryId`、`name`、`sortOrder`、`status`（`ACTIVE`｜`INACTIVE`）

- `GET` 回傳未過濾、未排序的完整清單（`sortOrder` 有回傳但沒實際套用排序）
- `POST` 無 `@Valid`，server 端補預設值：`status` → `ACTIVE`、`sortOrder` → `0`

> ⚠️ **已知缺陷**：`DELETE /api/admin/product-categories/{id}` 的路徑變數宣告為 `{id}`，
> 方法參數卻是 `@PathVariable Integer productCategoryId`，兩者名稱不符，Spring 無法綁定
> → 此端點目前**無法正常運作**（會拋 `MissingPathVariableException`，落到兜底處理回 500）。
> 修正方式：改為 `@PathVariable("id")` 或將路徑改成 `{productCategoryId}`。

---

## 4. 購物車

前綴 `/api/cart`，全部 🔒 需登入且具會員身分。購物車**只收商品**，課程走直接結帳。

| 方法 | 路徑 | 成功狀態 | 說明 |
|---|---|---|---|
| GET | `/api/cart` | 200 | 取得購物車 |
| POST | `/api/cart/items` | **201** | 加入商品（同商品會**累加**數量） |
| PUT | `/api/cart/items/{cartItemId}` | 200 | 修改數量（**絕對值設定**，非累加） |
| DELETE | `/api/cart/items/{cartItemId}` | **204** | 移除單一項目 |
| DELETE | `/api/cart` | **204** | 清空購物車 |

Request：
- `AddCartItemRequest`：`productId` `@NotNull`、`quantity` Long `@NotNull` `@Min(1)`
- `UpdateCartItemRequest`：`quantity` Long `@NotNull` `@Min(1)`

Response：
- `CartResponse`：`items` `List<CartItemResponse>`、`totalAmount` BigDecimal
- `CartItemResponse`：`cartItemId`、`productId`、`productName`、`imageUrl`、`unitPrice`、`quantity`、`subtotal`

錯誤：`400 INVALID_QUANTITY`；`404 PRODUCT_NOT_FOUND`／`CART_ITEM_NOT_FOUND`；`400 PRODUCT_UNAVAILABLE`；`409 INSUFFICIENT_STOCK`（以**累加後的總量**檢查）；`403 FORBIDDEN_CART_ACCESS`（操作他人項目）

> 加入商品時會鎖定會員資料列，避免並發加入同商品撞上唯一索引 `UX_cart_user_product`。

---

## 5. 訂單

### 5.1 會員端（`/api/orders`，🔒 需登入）

| 方法 | 路徑 | 成功狀態 | 說明 |
|---|---|---|---|
| GET | `/api/orders` | 200 | 我的訂單列表（`createdAt DESC`） |
| GET | `/api/orders/{orderId}` | 200 | 訂單明細 |
| POST | `/api/orders` | **201** | 購物車結帳 |
| POST | `/api/orders/direct` | **201** | 私教課直接結帳 |
| POST | `/api/orders/direct/subscription` | **201** | 訂閱方案下單 |
| POST | `/api/orders/direct/subscription/checkout` | **201** | 訂閱下單 + 直接取得綠界表單 |
| POST | `/api/orders/{orderId}/cancel` | 200 | 取消／申請退款 |

`OrderStatus`：`UNPAID`｜`PAID`｜`SHIPPED`｜`COMPLETED`｜`CANCELLED`｜`FAILED`｜`REFUNDED`

**回應 DTO**
- `OrderSummaryResponse`：`orderId` Long、`merchantTradeNo`、`totalAmount`、`status`、`createdAt`
- `OrderDetailResponse`：上述欄位 + `items` `List<OrderItemResponse>`
- `OrderItemResponse`：`orderItemId`、`productId`、`privateCourseId`、`productNameAtBuy`、`priceAtBuy`、`quantity`、`lineAmount`
  （`productId` 與 `privateCourseId` **恰好一個非空**）

**Request DTO**
- `CreateOrderRequest`（**body 可省略**）：`couponCode` `@Size(max=30)`、`receiverName` `@Size(max=50)`、`receiverPhone` `@Size(max=20)`、`receiverAddress` `@Size(max=200)`。收件人未填時自動沿用會員姓名／電話。
- `DirectCheckoutRequest`：`privateCourseId` `@NotNull`、`quantity` Long `@NotNull` `@Positive`、`couponCode` `@Size(max=30)`
- `SubscriptionCheckoutRequest`：`subscriptionPlanId` `@NotNull`、`couponCode` `@Size(max=30)`
- `CancelOrderRequest`（**body 可省略**）：`reason` `@Size(max=500)`

`POST /api/orders/direct/subscription/checkout` 回 `SubscriptionCheckoutResponse`：`order`（`OrderDetailResponse`）、`ecpayForm`（`Map<String,String>`，見 [6.1](#61-建立綠界付款表單)）。前端拿到後直接組表單送出，不必再打一次 `/api/payments/ecpay/checkout/{orderId}`。

> 訂閱訂單**沒有 order item**（方案記在 `order.subscriptionPlan`），所以 `items` 會是空陣列。

**訂閱雙向管制**（兩個方向都在 `OrderService` 強制）：
- 沒有有效訂閱 → 買私教課被擋：`403 SUBSCRIPTION_REQUIRED`「需先訂閱會員方案才能購買私教課程」
- 已有未到期訂閱 → 再買訂閱被擋：`409`「尚有未到期的訂閱，到期後才能購買下一期」

主要錯誤：

| 情況 | 回應 |
|---|---|
| 購物車為空 | `409` 購物車是空的，無法建立訂單 |
| 商品非 ACTIVE | `400 PRODUCT_UNAVAILABLE` |
| 庫存不足 | `409 INSUFFICIENT_STOCK` |
| 私教課非 ACTIVE | `400 DIRECT_CHECKOUT_NOT_ALLOWED` |
| 訂閱方案非 ACTIVE | `409` 此訂閱方案目前無法購買 |
| 優惠券問題 | `409` 優惠券已失效或不在使用期限內／此優惠券不適用於本次購買的品項類型／未達優惠券最低消費金額／尚未持有此優惠券或已使用過 |
| 訂單不存在或非本人 | `404` 找不到訂單資料 |

**取消的兩種行為**（`POST /{orderId}/cancel`）：

| 原狀態 | 行為 |
|---|---|
| `UNPAID` | 直接轉 `CANCELLED`，回補庫存、優惠券還原為 `UNUSED` |
| `PAID` | 建立 `PENDING` 退款單，**訂單狀態維持 `PAID`**，待管理員完成退款 |
| 其他 | `409` 只有未付款的訂單可以取消 |

### 5.2 管理端（`/api/admin/orders`，🛡️ ADMIN）

| 方法 | 路徑 | 成功狀態 |
|---|---|---|
| GET | `/reports/overview?from=&to=` | 200 |
| GET | `/refunds/pending` | 200 |
| POST | `/refunds/{refundId}/complete` | **204** |

- `from`／`to` 為 `LocalDateTime`，ISO 格式（`2026-01-01T00:00:00`），**皆必填**
- `OrderOverviewResponse`：`from`、`to`、`totalOrders` long、`totalRevenue`、`countByStatus` Map、`revenueByStatus` Map、`countByPaymentMethod` Map
- `PendingRefundResponse`：`refundId`、`orderId`、`merchantTradeNo`、`refundNo`、`amount`、`reason`、`requestedAt`

完成退款會：退款單轉 `COMPLETED` → 回補庫存 → 優惠券還原 `UNUSED` → **若為訂閱訂單則將 `MemberSubscription` 轉 `CANCELLED`** → 訂單轉 `REFUNDED`。
錯誤：`404` 找不到退款紀錄；`409` 此退款已處理過／只有已付款的訂單可以標記為已退款

---

## 6. 金流（ECPay）

### 6.1 建立綠界付款表單

#### 🔒 `POST /api/payments/ecpay/checkout/{orderId}`

無 body、無 query。僅能對**自己的**訂單建立（`findByIdAndMemberId`）。
Response `200` `Map<String,String>`：

| Key | 值 |
|---|---|
| `MerchantID` | `app.ecpay.merchant-id` |
| `MerchantTradeNo` | `GYM` + `yyyyMMddHHmmss` + 3 碼亂數 |
| `MerchantTradeDate` | 台北時間 `yyyy/MM/dd HH:mm:ss` |
| `PaymentType` | `aio` |
| `TotalAmount` | 金額四捨五入到整數 |
| `TradeDesc` | `訂單` + orderNo |
| `ItemName` | 品名以 `#` 串接；訂閱訂單無品項時為 `訂單` + orderNo |
| `ReturnURL` | `app.ecpay.return-url` |
| `ClientBackURL` | `app.ecpay.client-back-url` |
| `ChoosePayment` | `Credit` |
| `EncryptType` | `1` |
| `CheckMacValue` | 上述 11 欄的 SHA-256 檢查碼（大寫 hex） |
| `ActionUrl` | 綠界結帳網址 |

> 回的是 JSON map，**不是自動送出的 HTML 表單**，前端需自行組表單 POST 到 `ActionUrl`。
> `ActionUrl` 是在算完檢查碼之後才加入的，**不列入 `CheckMacValue` 計算**。

錯誤：`404` 找不到訂單資料（不存在或非本人）

### 6.2 綠界回調

#### 🔓 `POST /api/payments/ecpay/notify`

- Content-Type：`application/x-www-form-urlencoded`（綠界 server-to-server 通知，不帶登入態）
- 以 `@RequestParam Map<String,String>` 全收，無固定 DTO
- **回應永遠是純文字 `1|OK`（`text/plain`, HTTP 200）**，即使驗章失敗、找不到訂單或內部例外亦然 —— 回其他內容會讓綠界判定失敗並不斷重送，錯誤改記錄於 `PaymentWebhook` 與 log

實際使用的欄位：`CheckMacValue`（驗章）、`MerchantTradeNo`（對應訂單）、`TradeNo`、`RtnCode`（`1` 為成功）、`RtnMsg`、`PaymentType`。其餘欄位一併納入驗章與原始 payload 保存。

處理流程：驗章 → **冪等檢查**（`MerchantTradeNo + "-" + RtnCode` 為 event key，重複通知直接略過，不會重複出貨）→ 寫 `PaymentWebhook` → 建立／更新 `PaymentTransaction` → `RtnCode=1` 時訂單轉 `PAID` 並執行 `fulfillPaidOrder`，否則轉 `FAILED`。

> **履約時機在付款成功後，不在下單時**：私教堂數（`MemberCourseQuota`）與訂閱啟用（`MemberSubscription`）都在 `fulfillPaidOrder` 才發生；商品庫存則相反，是在建立訂單時就先扣。

> 另無獨立的瀏覽器導回端點 —— `ClientBackURL` 直接指向前端 SPA。

---

## 7. 訂閱

| 方法 | 路徑 | 權限 | 說明 |
|---|---|---|---|
| GET | `/api/subscription-plans` | 🔓 | 可購買的方案（僅 `ACTIVE`） |
| GET | `/api/me/subscription` | 🔒 | 我的訂閱狀態 |

- `SubscriptionPlanResponse`：`subscriptionPlanId`、`name`、`description`、`price`、`durationDays`、`status`
- `SubscriptionResponse`：`subscriptionId`、`planName`、`subscriptionPlanId`、`orderId`、`startDate`、`endDate`、`status`、`createdAt`、`isActive`（計算值：`status = ACTIVE` 且 `endDate` 晚於現在）

取最新一筆（依 `endDate` 排序）。完全沒有訂閱紀錄時回 `404` 尚無任何訂閱紀錄。

---

## 8. 私教：教練時段與預約

### 8.1 教練端（`/api/trainer`，🏋️ TRAINER）

| 方法 | 路徑 | 成功狀態 | 說明 |
|---|---|---|---|
| POST | `/schedules` | **201** | 建立可預約時段 |
| GET | `/schedules` | 200 | 我的時段（含 `CANCELLED`） |
| PUT | `/schedules/{slotId}` | 200 | 修改時段 |
| DELETE | `/schedules/{slotId}` | 200 | 取消時段（軟刪除） |
| PUT | `/schedules/{bookingId}/checkin` | 200 | 核銷到場 |

> 教練身分由 token 解析（account → employee → trainer），**不接受前端傳入 trainerId**。
> 解析失敗回 `403 FORBIDDEN_ACCESS`「目前帳號沒有教練身分」。

- `CreateCoachSlotRequest`：`startAt`、`endAt`（皆 `@NotNull` `@Future`），另有跨欄檢查「結束時間必須晚於開始時間」
- `UpdateCoachSlotRequest`：同上，另加 `acknowledgeExistingBooking` boolean（**放在 body**）
- `DELETE` 的確認參數則是 query：`?acknowledgeExistingBooking=true`
- `CheckInRequest`：`checkInKey` String（**無 `@Valid`**）

`CoachSlotResponse`：`id`、`trainerId`、`startAt`、`endAt`、`status`（`AVAILABLE`｜`BOOKED`｜`CANCELLED`）
`CoachBookingResponse`：`id`、`coachSlotId`、`trainerId`、`startAt`、`endAt`、`status`（`PENDING`｜`COMPLETED`｜`CANCELLED`）、`checkInKey`、`remainingCourse`

主要錯誤：`409` 已有重疊時段／此時段已被取消，無法修改，請重新建立新時段／此預約目前無法核銷（可能已核銷或已取消）／核銷碼錯誤，請確認會員出示的核銷碼；`403` 無權存取此教練時段；`404` 查無此時段／查無此預約

> 核銷碼於建立預約時以 `SecureRandom` 產生，8 碼，字母表刻意排除易混淆的 I／O／0／1。

### 8.2 會員端（`/api/member`）

| 方法 | 路徑 | 權限 | 成功狀態 |
|---|---|---|---|
| GET | `/trainers` | 🔓 | 200 |
| GET | `/trainers/{trainerId}/schedules` | 🔓 | 200 |
| POST | `/schedules/{slotId}/booking` | 👤 | **201** |
| DELETE | `/schedules/{bookingId}/cancel` | 👤 | 200 |
| GET | `/bookings` | 👤 | 200 |

- `GET /trainers`：在職教練清單（公開），供會員瀏覽後決定要約哪位——是下一行時段查詢的前一步
  （`TrainerSummaryResponse`：`trainerId`、`name`、`specialty`、`status`）
- 查詢時段為公開（只回 `AVAILABLE`，依 `startAt` 遞增；未知 trainerId 回空陣列而非 404）
- 預約：自動選用**最早到期**的可用堂數包，客戶端無法指定
- `GET /bookings` 回 `CoachBookingHistoryResponse`：欄位同 `CoachBookingResponse`，但最後一欄是 `remainingCourseSnapshot`

> `remainingCourse`（動作後的即時餘額）與 `remainingCourseSnapshot`（該筆預約的歷史快照，**可能為 null**）意義不同，不可混用。null 代表該預約早於稽核紀錄上線，前端應顯示為「無紀錄」而非 0。

### 8.3 72 小時取消規則

`DELETE /api/member/schedules/{bookingId}/cancel?acknowledgeConsumption={bool}`

| 距上課時間 | 行為 |
|---|---|
| ≥ 72 小時 | 退還堂數（`reserved-1, remaining+1`） |
| < 72 小時 | 須帶 `acknowledgeConsumption=true`，**堂數視同消耗不退還**（`reserved-1, used+1`） |

未帶確認參數時回 `409 CONFIRMATION_REQUIRED`「預約時間72小時之內取消，將扣除堂數，請確認」。

### 8.4 堂數帳本
`MemberCourseQuota` 恆等式：`total = remaining + reserved + used`

| 動作 | 變化 |
|---|---|
| `book()` 預約 | `remaining-1, reserved+1` |
| `consume()` 核銷／逾期取消 | `reserved-1, used+1` |
| `refund()` 準時取消／教練取消 | `reserved-1, remaining+1` |

每次異動都會寫 `SessionDeductionLog`（`BOOK`／`CHECKIN`／`CANCEL_REFUND`／`CANCEL_CONSUME`）。

### 8.5 併發保護
`CoachSlot` 與 `MemberCourseQuota` 皆有 JPA `@Version`；並發搶同一時段時，最後防線是 `markAsBooked` 的狀態檢查（回 `409` 此時段目前無法預約）或 `409 CONCURRENT_UPDATE_CONFLICT`。

### 8.6 兩種二次確認流程

| 參數 | 使用者 | 要確認的事 |
|---|---|---|
| `acknowledgeExistingBooking` | 教練／管理員 | 時段或場次已有人預約，取消／修改會影響會員 |
| `acknowledgeConsumption` | 會員 | 72 小時內取消會扣掉堂數 |

兩者都以 `409 CONFIRMATION_REQUIRED` 觸發。前端應以 `code` 判斷「跳確認框後重送」還是「單純錯誤」：`CONFIRMATION_REQUIRED` 屬前者，`BUSINESS_RULE_VIOLATION` 屬後者。

### 8.7 教練檔案（TrainerController）

| 方法 | 路徑 | 權限 | 成功狀態 |
|---|---|---|---|
| POST | `/api/employees/{employeeId}/trainer` | 🛡️ ADMIN（filter + `@PreAuthorize` 雙重把關） | **201** |
| GET | `/api/trainers` | 🔓 | 200 |
| GET | `/api/trainers/{trainerId}` | 🔓 | 200 |
| GET | `/api/member/trainers/chat-directory` | 🛡️ MEMBER（`anyRequest().authenticated()` + `@PreAuthorize`） | 200 |

#### 🛡️ `POST /api/employees/{employeeId}/trainer` —— 員工升級為教練

Request `TrainerCreateRequest`：

| 欄位 | 型別 | 驗證 |
|---|---|---|
| `specialty` | String | `@NotBlank` `@Size(max=255)`，儲存前去除前後空白 |
| `experience` | String | `@NotBlank` `@Size(max=1000)`，儲存前去除前後空白 |
| `bio` | String | `@NotBlank` `@Size(max=5000)`，儲存前去除前後空白 |
| `profileImageUrl` | String | 選填，`@Size(max=255)`，空白字串正規化為 `null` |

> **不接受 `status`** —— 建立時一律由後端固定為 `ACTIVE`，避免呼叫端任意指定狀態。

行為：建立前以 `findByIdForUpdate` 鎖住 Employee 列，同一員工的並發升級請求會序列化，
確保回報精準的「已經是教練」而非資料庫 constraint 的通用錯誤。
一位員工一生只會有一筆 Trainer（revive-not-recreate）。建立時一併把姓名／email
快照進 `Trainer.firstName/lastName/email`（見 Trainer 實體註解），不留白等下次重啟才補。

錯誤：

| 情況 | 回應 |
|---|---|
| 員工不存在 | `404` 找不到此員工 |
| 員工非在職（非 ACTIVE 或已軟刪除） | `409` 此員工目前不是在職狀態，無法建立教練身分 |
| 已是教練 | `409` 此員工已經是教練 |
| 曾是教練（已軟刪除） | `409` 此員工先前曾是教練，須改走 ADMIN 恢復流程，不能重新建立（見 §8.7 恢復） |

#### 🔓 `GET /api/trainers`／`GET /api/trainers/{trainerId}` —— 公開教練介紹

Response `TrainerResponse`：

| 欄位 | 型別 | 說明 |
|---|---|---|
| `trainerId` | Integer | |
| `displayName` | String | 姓＋名組合 |
| `specialty` | String | 專長 |
| `experience` | String | 經歷 |
| `bio` | String | 自我介紹 |
| `profileImageUrl` | String | 個人圖片 URL，可為 `null` |
| `status` | TrainerStatus | `ACTIVE`｜`UNAVAILABLE`，皆公開；已軟刪除者一律 404 |

> **不含 `employeeId`**——匿名端點不可外洩此內部關聯 FK。聊天室（§14）需要 `employeeId`
> 定址對話對象，改走下面登入限定的專用端點，不共用這個公開 DTO。

#### 🛡️ `GET`／`PUT /api/trainer/profile` —— 教練本人查詢／修改專業資料

權限 `hasRole('TRAINER')`（`SecurityConfig` 已有 `/api/trainer/**` URL matcher，方法層 `@PreAuthorize` 為第二層防護）。一般個資（姓名／電話）不在這裡，改走既有的
`GET`／`PUT /api/employee/profile`（§8.6）；Email／密碼改走帳號安全流程，兩者都不接受本端點處理。

`ROLE_TRAINER` 由 `AccountAuthorityResolver` 算出：員工 ACTIVE 且有對應未軟刪除的 Trainer
資料列才授予——只看 `Trainer.deletedAt`，不看 `Trainer.status`，故 `UNAVAILABLE` 教練仍能
正常使用本端點（規格明訂：可服務狀態與教練身分本身脫鉤）。

Response `TrainerProfileResponse`：`trainerId`／`employeeId`／`specialty`／`experience`／
`bio`／`profileImageUrl`／`status`。不含 `createdAt`／`updatedAt`（比照
`EmployeeProfileResponse` 省略稽核欄位的既有風格）。

Request `TrainerProfileUpdateRequest`（PUT）：`specialty`／`experience`／`bio`／
`profileImageUrl`，驗證規則同建立教練。**不可透過此端點修改** `status`／`deletedAt`／
`employeeId`／`trainerId`／一般個資／Email／密碼。

#### 🛡️ ADMIN 教練管理（AdminTrainerController，`/api/admin/trainers/**`）

| 方法 | 路徑 | 成功狀態 |
|---|---|---|
| GET | `/api/admin/trainers` | 200 |
| GET | `/api/admin/trainers/{trainerId}` | 200（可查已軟刪除） |
| PUT | `/api/admin/trainers/{trainerId}` | 200 |
| POST | `/api/admin/trainers/{trainerId}/status` | 200 |
| GET | `/api/admin/trainers/{trainerId}/suspension-check` | 200 |
| POST | `/api/admin/trainers/{trainerId}/suspend` | **204** |
| POST | `/api/admin/trainers/{trainerId}/restore` | 200 |

權限皆為 `hasRole('ADMIN')`（`/api/admin/**` URL matcher + 方法層 `@PreAuthorize` 雙重把關，
比照 `AdminEmployeeController`）。建立教練刻意留在既有的
`POST /api/employees/{employeeId}/trainer`，不搬進 `/api/admin/trainers/**` 下——那個路徑
已上線且有測試覆蓋，搬家的一致性收益不值得冒風險去動 `SecurityConfig` 與既有測試。

**`GET /api/admin/trainers`** 查詢參數：`keyword`（不分大小寫比對姓名／登入 email／專長）、
`status`（`TrainerStatus`，不傳＝全部）、`deleted`（預設 `false`；`true` 只看已停用）、
`page`（預設 0）、`size`（預設 10，上限 100）、`sort`（白名單：`trainerId`／`name`／`status`／
`createdAt`／`updatedAt`，非法值回退 `trainerId`）、`direction`（`desc` 才降冪，其餘皆升冪）。
Response `Page<AdminTrainerSummaryResponse>`：`trainerId`／`employeeId`／`displayName`／
`email`／`specialty`／`status`／`deletedAt`／`createdAt`。

**`GET /api/admin/trainers/{trainerId}`** Response `AdminTrainerResponse`：上述摘要欄位
再加 `employeeStatus`（底下員工目前在職狀態，方便判斷能否恢復）／`experience`／`bio`／
`profileImageUrl`／`updatedAt`。不過濾 `deletedAt`——ADMIN 可查已停用的教練。

**`PUT /api/admin/trainers/{trainerId}`** Request `AdminTrainerUpdateRequest`：
`specialty`／`experience`／`bio`／`profileImageUrl`，驗證規則與建立教練相同。員工姓名／
電話等一般個資改走 `PUT /api/admin/employees/{employeeId}`（§8.6），不在這裡處理。

**`POST /api/admin/trainers/{trainerId}/status`** Request `AdminTrainerStatusUpdateRequest`：
`{"status": "ACTIVE" | "UNAVAILABLE"}`（`@NotNull`）。規則：已軟刪除者 `409`；狀態與現況
相同 `409`「此教練已經是指定狀態」；欲設為 `ACTIVE` 但底下員工非 ACTIVE 時 `409`
「此教練所屬員工目前不是在職狀態，無法設為可服務」（設為 `UNAVAILABLE` 不受此限）。
設為 `UNAVAILABLE` 不會自動取消既有排班／預約／課程。

**`GET /api/admin/trainers/{trainerId}/suspension-check`** 停用預檢，只供管理畫面提示，
正式停用仍會在交易內重新檢查。Response `TrainerSuspensionCheckResponse`：

```json
{
  "canSuspend": false,
  "blockers": {
    "openSlots": 2,
    "activeBookings": 1,
    "activePrivateCourses": 1,
    "upcomingGroupClassSessions": 3
  }
}
```

四項阻塞條件（皆為「未完成」才算數，已取消／已結束不算）：

| 阻塞項目 | 判斷條件 |
|---|---|
| `openSlots` | `CoachSlot.status <> CANCELLED AND endAt > now`（`status` 本身沒有到期自動轉態，須疊加時間條件） |
| `activeBookings` | `CoachBooking.status = PENDING`（`CoachBookingStatus` 只有 `PENDING`／`COMPLETED`／`CANCELLED`，沒有 `CONFIRMED`） |
| `activePrivateCourses` | `PrivateCourse.trainerId = 此教練 AND status = ACTIVE`（`PrivateCourse` 沒有 `deletedAt` 或到期日欄位，只看 `status`；下架／重新上架見下方 §8.10） |
| `upcomingGroupClassSessions` | 先查 `GroupCourse.trainerId = 此教練`，再查其底下 `GroupClassSession.status = SCHEDULED AND endAt > now`（教練綁在 `GroupCourse` 主檔，`GroupClassSession` 本身沒有教練欄位，代課設計已捨棄；用 `endAt` 而非 `startAt`——場次已開始但尚未結束時仍算未完成，見 code review 發現） |

**`POST /api/admin/trainers/{trainerId}/suspend`** 軟刪除（`deletedAt = now`），成功 `204`
無回應內容。已停用者 `404`（比照 `AdminMemberService.lockOrThrow()`，不特別區分
「找不到」與「已軟刪除」）。有任一阻塞條件時 `409`「教練仍有未完成業務，無法停用」，
訊息內含四項阻塞數量。不刪除任何歷史資料，也不自動取消既有排班／預約／課程。

**`POST /api/admin/trainers/{trainerId}/restore`** 恢復既有已軟刪除的 Trainer 資料列
（revive-not-recreate，`trainerId` 不變，不建立新資料）。條件：Trainer 須存在且已軟刪除
（否則 `409`「此教練目前並未停用，無需恢復」）；底下 Employee 須存在、未軟刪除且 ACTIVE
（否則 `409`）。成功後固定回到 `status = UNAVAILABLE`（即使停用前是 `ACTIVE`，也不直接跳回
`ACTIVE`——須待 ADMIN 重新確認資料後另外呼叫狀態切換），並即時同步 Trainer 的姓名快照
（停用期間 Employee 姓名可能已變動，`SchemaFixupRunner` 的整批同步只在應用重啟時才跑）。

#### 停用後的跨模組防護（§9）

`TrainerService.assertAvailableForNewBusiness(Trainer)` 是純檢查邏輯：Trainer 未軟刪除、
Employee 未軟刪除、Employee ACTIVE、Trainer ACTIVE 四個條件任一不成立即 `409`。

**必須搭配悲觀鎖定使用，不能只傳一個已經讀到的 Trainer 物件**（code review 發現，
2026-08-19）：先前版本讓三個呼叫端各自把未鎖定讀到的 Trainer 傳進這個方法，
與 `AdminTrainerService.suspend()`（先悲觀鎖定 Trainer 列才檢查未完成業務）之間
存在真實的競態條件——新增業務的交易可能在讀到 `ACTIVE` 之後、真正寫入（commit）之前，
被停用交易搶先鎖定、檢查（此時新業務還沒 commit，查不到）、成功停用，導致已停用教練
仍產生新排班／預約／場次。修正後改用 `TrainerService.lockAndAssertAvailableForNewBusiness(Integer trainerId)`：
方法內部自己以 `findByIdForUpdate()` 悲觀鎖定 Trainer 列，跟 `suspend()` 搶同一顆鎖、
同一個順序，兩邊只要有一邊先取得鎖，另一邊就會被擋到前者交易 commit 之後才能繼續。
已串接於：

| 呼叫端 | 時機 |
|---|---|
| `CoachSlotService.createSlot()` | 教練新增排班時段 |
| `CoachBookingService.createBooking()` | 會員建立私教預約（即使時段本身仍顯示 `AVAILABLE`） |
| `GroupClassService.createSession()` | ADMIN 建立團課場次 |
| `GroupCourseService.createCourse()` | ADMIN 建立團課主檔（指派教練） |
| `GroupCourseService.activateCourse()` | ADMIN 重新上架團課主檔，只在真的要 `INACTIVE`→`ACTIVE` 時才檢查 |
| `PrivateCourseService.createCourse()` | 尚無 Controller 對外開放（見下方 §8.10），但方法簽章已改為接收 `trainerId` 並內建這道檢查，等實際串接端點時不必再補 |
| `PrivateCourseService.activateByAdmin()` | ADMIN 重新上架私教課商品（見下方 §8.10），只在真的要 `INACTIVE`→`ACTIVE` 時才檢查 |

> **兩輪 code review 修正歷程**：第一輪只在 `GroupClassService.createSession()`（場次建立）
> 加了這道檢查，`GroupCourseService.createCourse()`/`activateCourse()`（課程主檔）刻意不查，
> 理由是「真正影響會員報名的風險點是場次建立」。第二輪 code review 指出原規格§9 本來就把
> 「建立或指派 GroupClass」跟「建立或指派 GroupClassSession」列成兩個獨立防護點，且此時
> `PrivateCourse` 已經在「商品」層級加了這道檢查（見下方），兩個課程網域的防護時機不一致。
> 修正為兩層都查，不是互斥選項——課程主檔 `ACTIVE` 不代表底下每一場次建立時教練仍可服務。

**真實 SQL Server E2E 待辦**：Mockito 單元測試無法證明悲觀鎖定在正式資料庫下真的
序列化了兩個交易（純 mock 沒有真正的併發競爭）。「停用與新增排班／預約並行」的
測試案例尚未執行，仍是本階段公開的已知缺口。

#### 🛡️ `GET /api/member/trainers/chat-directory` —— 聊天室選教練用（會員限定）

Response `TrainerChatDirectoryResponse`：

| 欄位 | 型別 | 說明 |
|---|---|---|
| `trainerId` | Integer | |
| `employeeId` | Integer | 聊天訊息以此定址對話對象 |
| `displayName` | String | 姓＋名組合 |
| `specialty` | String | 專長 |

跟 `GET /api/trainers` 同一份資料來源（未軟刪除者皆回傳，ACTIVE／UNAVAILABLE 都在內），
只是多帶 `employeeId`。

> **後端訊息驗證同步強化（code review 發現，2026-08-19）**：聊天目錄只列教練，但
> `ChatSendRequest.employeeId()` 是前端自帶欄位，光靠這個目錄擋不住會員把值改成
> 任意在職員工（例如一般 EMPLOYEE 或 ADMIN，兩者都可能是 ACTIVE 但沒有 Trainer 身分）
> 的 id 也能建立聊天室——光是不外洩員工清單不代表後端真的驗證了對象是教練。
> `ChatService.requireActiveEmployee()` 已同步強化：除了原本的「ACTIVE 且未軟刪除」，
> 現在還要求該 employeeId 有對應且未軟刪除的 Trainer 資料列（判斷標準跟
> `AccountAuthorityResolver` 的 `ROLE_TRAINER` 一致，只看 `deletedAt`，不看
> `Trainer.status`——`UNAVAILABLE` 教練仍可被聊天，只是不能被預約）。

- 列表**不篩 status** —— `UNAVAILABLE`（如休假中）教練同樣回傳，由前端依 `status` 自行停用預約入口；只排除軟刪除者
- **刻意不帶 Employee 內部資料**（email／電話／地址／生日／審核資訊），公開端點不外洩個資
- 單筆查詢：不存在或已軟刪除 → `404` 找不到此教練

### 8.10 ADMIN 私教課下架／重新上架（AdminPrivateCourseController）

| 方法 | 路徑 | 成功狀態 |
|---|---|---|
| GET | `/api/admin/private-courses?trainerId=` | 200 |
| POST | `/api/admin/private-courses/{privateCourseId}/deactivate` | 200 |
| POST | `/api/admin/private-courses/{privateCourseId}/activate` | 200 |

權限 `hasRole('ADMIN')`（`/api/admin/**` URL matcher + 方法層 `@PreAuthorize` 雙重把關）。
**這不是完整的 PrivateCourse 後台管理**——建立／編輯／瀏覽仍未串接（見 §15.1 已知缺口），
新增這三個端點的直接原因是 8.7 節的教練停用檢查會擋在「名下還有生效中私教課」，
在此之前完全沒有任何 Controller 能把 `PrivateCourse.status` 設回 `INACTIVE`，等於
停用流程被卡死、無解（code review 發現，2026-08-19）。

`GET`：查詢指定教練名下所有私教課商品（不分狀態），供 ADMIN 決定要下架哪些商品，
Response 為 `List<PrivateCourseResponse>`。**下架**：冪等，已是 `INACTIVE` 直接回傳現有資料。
**重新上架**：只在真的要從 `INACTIVE`→`ACTIVE` 時才檢查教練可服務狀態（§9 跨模組防護），
`UNAVAILABLE`／已軟刪除的教練名下不可重新上架商品，回 `409`；已是 `ACTIVE` 視為
no-op，不重新檢查。

> **一般更新不可繞道改狀態（第二輪 code review 發現，2026-08-19）**：`updatePrivateCourse()`
> 先前用跟建立共用的 `PrivateCourseRequest`（含 `status`），能繞過上面「重新上架」的
> 教練鎖定與可服務狀態檢查，直接把 `status` 改回 `ACTIVE`。已改用不含 `status`／`trainerId`
> 的 `UpdatePrivateCourseRequest`（比照 `UpdateGroupCourseRequest` 拆分建立／編輯 DTO 的
> 既有慣例），狀態只能經由本節的下架／重新上架端點變更。`updatePrivateCourse()`／
> `deletePrivateCourse()` 的擁有者比對參數也一併從 `Trainer` 實體改成 `Integer trainerId`，
> 避免未來串接 Controller 時重演「呼叫端自己先不鎖定查出實體再傳進來」的模式。

> **courseCategoryId 先前被靜默忽略（第三輪 code review 發現，2026-08-19）**：
> `PrivateCourseRequest`／`UpdatePrivateCourseRequest` 都宣告了 `courseCategoryId`，
> 但 `createCourse()`／`updatePrivateCourse()` 先前完全沒有用它去查、設定
> `PrivateCourse.category`——呼叫端傳了會被無聲丟棄，課程永遠不會有分類。已補上
> （比照 `GroupCourseService` 既有的查詢＋`ResourceNotFoundException` 模式）：建立時
> 有帶就查、查無此分類回 `404`；更新時是 null-preserving（沒帶就維持原分類，不是清空）。

---

## 9. 團課

### 9.1 會員端

| 方法 | 路徑 | 權限 | 成功狀態 |
|---|---|---|---|
| GET | `/api/member/group-classes` | 🔓 | 200 |
| POST | `/api/member/group-classes/{sessionId}/booking` | 👤 | **201** |
| DELETE | `/api/member/group-classes/bookings/{bookingId}` | 👤 | 200 |

`GET` 只回未來且 `SCHEDULED` 的場次，依 `startAt` 遞增，**無任何篩選或分頁參數**。

`GroupClassSessionResponse`：`id`、`groupCourseId`、`trainerId`、`trainerName`、`courseName`、`roomName`、`startAt`、`endAt`、`capacity`、`bookedCount`、`remainingSeats`、`full`、`status`、`version`、`createdAt`、`updatedAt`

> ⚠️ 欄位名 `isFull` 經 Lombok 產生 getter `isFull()`，Jackson 序列化後的 **JSON 屬性名是 `full`**，不是 `isFull`。

`GroupClassBookingResponse`：`id`、`sessionId`、`courseName`、`roomName`、`startAt`、`endAt`、`status`（`BOOKED`｜`CANCELLED`）

> **團課不扣堂數**，資格條件是「持有有效訂閱」，與私教的堂數制不同。

錯誤：`409` 查無有效訂閱，請確認訂閱狀態／已報名此場次，請勿重複報名／此場次已額滿／此場次已取消，無法報名／場次已開始，無法取消；`403` 無權操作此報名；`404` 查無此團課場次／查無此報名紀錄

> 取消團課報名**沒有 72 小時規則、也不需二次確認**。

### 9.2 管理端（`/api/admin/group-classes`，🛡️ ADMIN）

| 方法 | 路徑 | 成功狀態 |
|---|---|---|
| GET | `/api/admin/group-classes` | 200 |
| POST | `/api/admin/group-classes` | **201** |
| PUT | `/api/admin/group-classes/{id}` | 200 |
| DELETE | `/api/admin/group-classes/{sessionId}` | 200 |

- `CreateGroupClassSessionRequest`：`groupCourseId` `@NotNull`、`roomName` `@NotBlank`、`startAt`／`endAt`（`@NotNull` `@Future`）、`capacity`（選填，null 時取課程的 `maxCapacity`）
- `UpdateGroupClassSessionRequest`：`roomName`、`startAt`、`endAt`、`capacity`（選填）、**`version` `@NotNull`**（樂觀鎖，須帶回讀取時拿到的值）；不含 `groupCourseId`，無法改綁課程

> 教練由 `GroupCourse` 決定，不由前端指定。
> 建立／修改都會檢查**教室時段重疊**（`409` 此教室已有重疊時段）。
> 修改時 `capacity` 不可小於目前已報名人數。
> `version` 不符回 `409` 此場次已被其他人修改，請重新載入最新資料。

`DELETE` 帶 `?acknowledgeExistingBooking=true` 才能取消已有報名的場次，會連鎖將所有 `BOOKED` 報名轉 `CANCELLED` 並同步扣減 `bookedCount`；未帶時回 `409 CONFIRMATION_REQUIRED`。

> 本 controller **沒有列表端點**，管理端讀取場次一樣走 `GET /api/member/group-classes`。

---

## 10. 課程分類

| 方法 | 路徑 | 權限 | 說明 |
|---|---|---|---|
| GET | `/api/course-categories` | 🔓 | 全部分類（未過濾、未排序） |
| POST | `/api/employee/course-categories` | 🧑‍💼 | 新增 → `200` |
| POST | `/api/employee/course-categories/{id}` | 🧑‍💼 | **更新**（注意是 POST 不是 PUT）→ `200` |
| DELETE | `/api/employee/course-categories/{id}` | 🧑‍💼 | 刪除 → `200`，body 為純字串 `課程分類已刪除` |

`CourseCategoryDto`（共用，**無驗證註解**）：`courseCategoryId`、`name`、`sortOrder`、`status`
新增時 server 補預設：`status` → `ACTIVE`、`sortOrder` → `0`。更新為部分更新（非 null 才套用）。

> ⚠️ **已知不一致**：更新遇到不存在的 id 時丟的是原生 `RuntimeException`，不是 `ApiException`，
> 會落到兜底處理回 **500 `INTERNAL_ERROR`**，而非預期的 404。
> 刪除則因使用 `deleteById`，不存在的 id **不會回 404**（靜默成功）。

### 10.1 團課主檔（`/api/admin/group-courses`，🛡️ ADMIN）

| 方法 | 路徑 | 成功狀態 | 說明 |
|---|---|---|---|
| POST | `/api/admin/group-courses` | **201** | 建立團課主檔 |
| GET | `/api/admin/group-courses` | 200 | 分頁查詢全部主檔（**含已下架**，支援 `page`／`size`／`search`） |
| PUT | `/api/admin/group-courses/{id}` | 200 | 修改主檔（授課教練與狀態不可經此變更） |
| DELETE | `/api/admin/group-courses/{id}` | 200 | 下架（`status` → `INACTIVE`，回傳更新後資料） |

`GroupCourseRequest`：`trainerId`（必填）、`name`、`description`、`imageUrl`、`courseCategoryId`（選填）、
`maxCapacity`、`isTrial`、`status`

> `isTrial`／`status` 未填時由 **Service** 補預設（`false`／`ACTIVE`），刻意不放在 DTO 欄位 initializer——
> Jackson 目前是先建空物件再逐一 setter 灌值，改用建構子反序列化時 initializer 不保證生效。

`GroupCourseResponse`：`id`、`name`、`trainerId`、`trainerName`、`description`、`imageUrl`、`maxCapacity`、
`isTrial`、`status`、`courseCategoryId`、`courseCategoryName`、`createdAt`、`updatedAt`

**下架語意**：冪等（已是 `INACTIVE` 再呼叫不報錯，直接回現況）；**不影響已排定的場次**，
只影響「之後能不能被選來建立新場次」。設計比照教練時段取消的軟刪除。

主要錯誤：`404` 查無此教練／查無此課程分類／查無此課程主檔

> 主檔（本節）與場次（[9](#9-團課)）職責分離：這裡只管「課程長怎樣」（教練、名稱、容量上限），
> 不涉及任何特定場次的時間、教室與報名狀態。

---

## 11. 優惠券

### 11.1 會員端（`/api/coupons`，🔒 需登入且具會員身分）

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/available` | 可領取的優惠券 |
| POST | `/claim/{couponId}` | 領取 |
| GET | `/my` | 我的全部優惠券（含已用、已過期） |
| GET | `/my-unused` | 我的可用優惠券（結帳選單用） |

`CouponResponse`：`couponId`、`code`、`name`、`type`（`CASH`｜`PERCENT`）、`value`、`minSpend`、`totalQuantity`、`claimedQuantity`、`startDate`、`endDate`、`status`（`ACTIVE`｜`INACTIVE`｜`EXPIRED`）、`applicableType`（`ALL`｜`PRODUCT`｜`COURSE`｜`SUBSCRIPTION`）、`createdAt`

`MemberCouponResponse`：`memberCouponId`、`coupon`（巢狀 `CouponResponse`）、`status`（`UNUSED`｜`USED`｜`EXPIRED`）、`claimedAt`、`usedAt`

領取規則：`status = ACTIVE`、未逾期、`claimedQuantity < totalQuantity`、且尚未領過。
領取時對優惠券資料列加悲觀鎖，避免超領。

> 注意：可領清單與領取動作**都不檢查 `startDate`**，尚未開始的優惠券也會出現並可領取。

錯誤：`404` 會員不存在／優惠券不存在或已下架；`409` 該優惠券已被停用／該優惠券已逾期／優惠券已被領取一空／您已經領取過此優惠券

### 11.2 員工端（`/api/employee/coupons`，🧑‍💼 EMPLOYEE）

類別層級已標註 `@PreAuthorize("hasRole('EMPLOYEE')")`，加上 filter 層規則，雙重把關。
注意是 **EMPLOYEE 而非 ADMIN** —— 任何在職員工都能管理優惠券。

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/api/employee/coupons` | 查詢（`keyword`、`status`、`startDate`、`endDate`） |
| POST | `/api/employee/coupons` | 新增 → `200` |
| PUT | `/api/employee/coupons/{couponId}` | 修改（全量覆寫）→ `200` |
| DELETE | `/api/employee/coupons/{couponId}` | 下架（軟刪除）→ `200`，body 為純字串 `優惠券已下架` |
| POST | `/api/employee/coupons/{couponId}/distribute-all` | 發放給全體 ACTIVE 會員 |
| POST | `/api/employee/coupons/{id}/distribute` | 發放給指定會員 |

`CouponRequest`：

| 欄位 | 型別 | 驗證 |
|---|---|---|
| `code` | String | `@NotBlank`（存檔時 trim + 轉大寫） |
| `name` | String | `@NotBlank` |
| `type` | CouponType | `@NotNull` |
| `value` | BigDecimal | `@NotNull` `@DecimalMin("0.01")` |
| `minSpend` | BigDecimal | `@NotNull` `@DecimalMin("0.00")` |
| `totalQuantity` | Integer | `@NotNull` `@Min(1)` |
| `startDate`／`endDate` | LocalDate | 選填 |
| `applicableType` | CouponApplicableType | 選填，預設 `ALL` |

`distribute` 的 body 是**裸 JSON 陣列**，例如 `[1,2,3]`；不存在的會員與重複持有者會被靜默略過。

回應為 `Map`：
- `distribute-all` → `{"success": true, "distributedCount": n, "messsage": "成功發放給{n}位會員"}`
- `distribute` → `{"success": true, "distributedCount": n, "message": "成功發放給 {n} 位會員"}`

> ⚠️ `distribute-all` 的鍵名誤植為 **`messsage`（三個 s）**，與 `distribute` 的 `message` 不一致，前端串接時請照實處理。

> ⚠️ 查詢端點的 `endDate` 參數**有接收但完全未使用**，不會產生任何過濾效果。
> 另外傳入 `startDate` 時，若有 `startDate` 為 null 的優惠券會 NPE 而回 500；`status` 值無法解析時則靜默忽略。

錯誤：`404` 優惠券不存在；`409` 優惠券代碼已存在／優惠券已停用，無法發放

---

## 12. 營運報表

前綴 `/api/admin/reports`，全部 🛡️ **ADMIN**（僅靠 URL 前綴把關，類別上沒有 `@PreAuthorize`）。

| 方法 | 路徑 | 成功狀態 | 說明 |
|---|---|---|---|
| GET | `/api/admin/reports` | 200 | 報表快照列表（`generatedAt DESC`） |
| GET | `/api/admin/reports/stats` | 200 | 即時銷售統計（不落地） |
| GET | `/api/admin/reports/{reportId}` | 200 | 報表明細 |
| GET | `/api/admin/reports/{reportId}/pdf` | 200 | **報表匯出 PDF** |
| POST | `/api/admin/reports` | **201** | 產生報表快照 |
| DELETE | `/api/admin/reports/{reportId}` | **204** | 刪除快照（連同明細） |

`GET /{reportId}/pdf` 回應：
- `Content-Type: application/pdf`
- `Content-Disposition: attachment; filename="operation-report-{id}.pdf"`
- 內容依序為：報表類型與期間、生成時間與產生者、總覽（訂單數／營收／折扣／平均客單價）、
  品類小計與占比、每日營收趨勢折線圖、訂單狀態與付款方式分佈、銷售排行（含占比）、
  退款與會員動態；內嵌中文字型，任何裝置顯示一致
- **只能匯出已生成的快照** —— 即時統計（`/stats`）要匯出前先 `POST` 生成快照，
  確保 PDF 上的數字永遠有資料庫紀錄可回查
- 快照已過時（`staleAt` 非 null）時，PDF 開頭會帶紅字警示，紙本流通後讀者仍知數據可能過時
- 錯誤：`404` 找不到報表資料；`500 PDF_GENERATION_FAILED` 報表 PDF 產生失敗，請稍後再試

`GET /stats` 參數：

| 參數 | 型別 | 必填 | 預設 |
|---|---|---|---|
| `type` | ReportItemType（`PRODUCT`｜`COURSE`｜`SUBSCRIPTION`） | 否 | 全部類型 |
| `from` | LocalDate `yyyy-MM-dd` | **是** | — |
| `to` | LocalDate `yyyy-MM-dd` | **是** | — |
| `top` | int | 否 | `10` |

`POST` Request `GenerateReportRequest`：`reportType`（`DAILY`｜`MONTHLY`｜`CUSTOM`）、`periodStart`、`periodEnd`，皆 `@NotNull`。

回應 DTO：
- `ReportSummaryResponse`：`id`、`reportType`、`periodStart`、`periodEnd`、`orderCount` Long、`totalAmount`、`discountAmount`、`generatedAt`、`staleAt`
- `ReportDetailResponse`：上述 + `items` `List<ReportSalesItemResponse>`，以及延伸統計：
  `statusBreakdown`／`paymentBreakdown` `List<ReportBreakdownResponse>`（`itemKey`、`orderCount`、`amount`；
  付款方式的 `amount` 為 null）、`dailyTotals` `List<ReportDailyTotalResponse>`（`statDate`、`orderCount`、`revenue`）、
  `refundCount`、`refundAmount`、`newMemberCount`、`newSubscriptionCount`

> 延伸統計於 2026-08-17 加入，與總覽同一份快照凍結（趨勢資料不於讀取時重算，
> 避免退款後趨勢與總覽變成兩個口徑）。此前生成的舊快照這些欄位為 null、清單為空，前端需容忍。
> 分佈的時間軸為**下單日**（看訂單量），與營收的付款日口徑不同。
- `SalesStatsResponse`：`periodStart`、`periodEnd`、`productType`、`totalQuantity` Long、`grossAmount`、`ranking`
- `ReportSalesItemResponse`：`rankNo`、`productId`、`productType`、`productName`、`quantitySold` Long、`grossAmount`

> `staleAt` 非 null 代表快照涵蓋期間內事後發生過退款，數字已過時，建議重新產生。
> 日期區間**頭尾皆含**；營收只計 `PAID`／`SHIPPED`／`COMPLETED` 且以 `paid_at` 為時間軸。
> 重複產生同一組 `(reportType, periodStart, periodEnd)` 會先刪舊快照再寫入。

錯誤：`404` 找不到報表資料；`409` 排行筆數須介於 1 到 100／起始日期不可晚於結束日期／統計區間不可超過 366 天

---

## 13. AI 健康

### 13.1 InBody 紀錄（`/api/inbody`，🔒 需登入）

| 方法 | 路徑 | 成功狀態 |
|---|---|---|
| GET | `/api/inbody` | 200 |
| GET | `/api/inbody/{id}` | 200 |
| POST | `/api/inbody` | **201** |
| PUT | `/api/inbody/{id}` | 200 |
| DELETE | `/api/inbody/{id}` | **204** |

`InbodyRecordRequest`：

| 欄位 | 型別 | 驗證 |
|---|---|---|
| `updateDate` | LocalDate | `@NotNull` `@PastOrPresent` |
| `height` | BigDecimal | 50.00 ~ 250.00，可空 |
| `weight` | BigDecimal | 20.00 ~ 300.00，可空 |
| `bodyFat` | BigDecimal | 0.00 ~ 100.00，可空 |
| `muscleMass` | BigDecimal | 0.00 ~ 150.00，可空 |
| `bmi` | BigDecimal | 10.00 ~ 80.00，可空 |

跨欄規則：五項量測值**至少填一項**，否則 `400 MISSING_INBODY_MEASUREMENT`。

`InbodyRecordResponse`：`id`、`updateDate`、`height`、`weight`、`bodyFat`、`muscleMass`、`bmi`

- 每位會員同一天只能有一筆 → `409 DUPLICATE_INBODY_RECORD`
- `PUT` 為全量覆寫；`DELETE` 為軟刪除（保留資料列以維持與 AI 課表的關聯完整性）
- 他人紀錄 → `403 FORBIDDEN_INBODY_ACCESS`

### 13.2 AI 課表（`/api/ai-plans`，🔒 需登入）

| 方法 | 路徑 | 成功狀態 | 說明 |
|---|---|---|---|
| POST | `/api/ai-plans` | **201** | 生成課表（**非同步**） |
| GET | `/api/ai-plans` | 200 | 我的課表列表 |
| GET | `/api/ai-plans/{id}/pdf` | 200 | 下載 PDF |

`GeneratePlanRequest`：`inbodyRecordId` `@NotNull`、`goal` `@NotBlank`（僅接受 `FAT_LOSS`／`MUSCLE_GAIN`／`MAINTAIN`）

`AiHealthPlanResponse`：`id`、`inbodyRecordId`、`goal`、`planContent`（Gemini 產生的 JSON 字串）、`modelName`、`createdAt`

> `POST` 回傳 `CompletableFuture`，走 Spring MVC async，逾時上限 130 秒（超過回 `503 SERVICE_TIMEOUT`）。

`GET /{id}/pdf` 回應：
- `Content-Type: application/pdf`
- `Content-Disposition: attachment; filename="ai-health-plan-{id}.pdf"`
- 內容含課表摘要、每日訓練表、飲食建議，以及體重／體脂率／肌肉量趨勢圖（各項至少要 2 筆資料才繪製，否則顯示「量測紀錄不足」）

錯誤：`400 INVALID_GOAL`；`404 INBODY_RECORD_NOT_FOUND`／`AI_HEALTH_PLAN_NOT_FOUND`；`403 FORBIDDEN_INBODY_ACCESS`／`FORBIDDEN_AI_HEALTH_PLAN_ACCESS`；`503 AI_SERVICE_UNAVAILABLE`；`500 PDF_GENERATION_FAILED`

### 13.3 聊天訊息查詢（REST）

**訊息傳送只能透過 WebSocket**，REST 僅供查詢歷史與已讀狀態。

會員端（🔒）：

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/api/chat/history` | 歷史訊息（分頁） |
| PATCH | `/api/chat/read` | 標記已讀 → `204` |
| GET | `/api/chat/unread-count` | 未讀數 |
| POST | `/api/chat/recommend-course` | 觸發 AI 課程推薦 → **`202`**，結果稍後由 WebSocket 推送 |

員工端（🧑‍💼 EMPLOYEE）：

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/api/employee/chat/history` | 需帶 `memberId` |
| PATCH | `/api/employee/chat/read` | 需帶 `memberId` → `204` |
| GET | `/api/employee/chat/unread-count` | 未讀數 |

`/api/chat/history` 參數：`counterpartType`（`EMPLOYEE`｜`AI`，**必填**）、`employeeId`（選填）、`page`（預設 0）、`size`（預設 20），依 `createdAt DESC`。

> `counterpartType=EMPLOYEE` 時**必須**帶 `employeeId`，否則 `409`「查詢與教練的對話紀錄時必須指定 employeeId」。

`ChatMessageDTO`：`id`、`roomId`、`counterpartType`、`counterpartLabel`（`AI顧問` 或 `教練`）、`employeeId`、`senderIsMember`、`mine`、`content`、`read`、`readAt`、`createdAt`
`ChatUnreadCountResponse`：`unread` long

---

## 14. WebSocket 聊天室

### 14.1 連線

| 項目 | 值 |
|---|---|
| 端點 | `/ws-chat` |
| 協定 | STOMP over **SockJS** |
| 允許來源 | 同 `app.cors.allowed-origins` |
| Broker | 記憶體內建 simple broker（不需 RabbitMQ／ActiveMQ） |
| 應用前綴 | `/app` |
| 廣播前綴 | `/topic`、`/user` |

### 14.2 認證

**在 STOMP `CONNECT` frame 帶原生 header**（不是 HTTP header）：

```
Authorization: Bearer <token>
```

SockJS 交握本身（`GET /ws-chat/info` 等）在 `SecurityConfig` 放行，真正的驗證發生在 CONNECT 階段，由 `StompAuthChannelInterceptor` 處理，驗證後將 `StompPrincipal(accountId)` 綁定於連線。

> **此驗證在所有 profile 都生效，dev 也不例外**，本機測試仍須先呼叫登入 API 取得 token。

CONNECT 失敗訊息：聊天室連線缺少 Authorization: Bearer \<token\>／聊天室連線的 token 無效或已過期／聊天室連線對應的帳號不存在或已停用

### 14.3 訂閱目的地

```
/topic/room/{roomId}
```

`roomId` 格式（正規式 `^/topic/room/member_(\d+)_(ai|employee_(\d+))$`）：

| 格式 | 用途 |
|---|---|
| `member_{memberId}_ai` | 會員與 AI 顧問的對話 |
| `member_{memberId}_employee_{employeeId}` | 會員與教練／員工的對話 |

**訂閱時授權**：房間 topic 是廣播頻道，不能只靠難猜的 room id 保護。訂閱者必須是該房間的 ACTIVE 會員本人，或該房間的 ACTIVE 員工本人。
錯誤：聊天室訂閱目的地格式錯誤／聊天室連線尚未完成驗證／無權訂閱此聊天室

另需訂閱 `/user/queue/errors` 以接收個人錯誤訊息（格式為 `ApiErrorResponse`）。

### 14.4 傳送目的地

| 目的地 | 傳送者 | Payload |
|---|---|---|
| `/app/chat.send` | 會員 | `ChatSendRequest` |
| `/app/chat.reply` | 員工 | `ChatReplyRequest` |

- `ChatSendRequest`：`content`（`@NotBlank` `@Size(max=500)`）、`counterpartType`（`@NotNull`）、`employeeId`
  （`EMPLOYEE` 時必填、`AI` 時必須為 null）
- `ChatReplyRequest`：`content`（`@NotBlank` `@Size(max=500)`）、`memberId`（`@NotNull`）

> 傳送者身分一律取自連線的認證資訊，**不採用 payload 帶的身分**。
> 訊息一律先寫入資料庫再廣播；`counterpartType = AI` 時另會非同步觸發 AI 回覆，稍後以第二則訊息推送到同一個 topic。
> 廣播版本的 `ChatMessageDTO` 其 `mine` 為 **null**，前端需自行以 `senderIsMember` 與自身角色比對判斷。

---

## 15. 已知問題彙整

### 15.1 尚未修復

| 位置 | 問題 |
|---|---|
| `DELETE /api/admin/product-categories/{id}` | 路徑變數 `{id}` 與參數 `productCategoryId` 名稱不符，端點無法運作（回 500） |
| `GET /api/employee/coupons` | `endDate` 參數有接收但未使用；`startDate` 遇到 null 值資料會 NPE 回 500 |
| `POST /api/employee/course-categories/{id}` | 找不到 id 時丟原生 `RuntimeException`，回 500 而非 404 |
| `DELETE /api/employee/course-categories/{id}` | 使用 `deleteById`，不存在的 id 靜默成功，不回 404 |
| `distribute-all` 回應 | 鍵名誤植為 `messsage`（三個 s）；同檔的 `distribute` 則是正確的 `message` |
| `POST /api/admin/products/upload` | 命名誤導——實際只收 JSON 建立商品，**不是上傳圖片**（專案目前無檔案上傳能力） |
| 商品評論 | `GET /api/products/{id}/comments`、`POST /api/comments` 前端已寫但**後端未實作**；前端以 `skipGlobalError` 靜默處理 |
| 私教課（PrivateCourse） | `PrivateCourseService` 的建立／編輯／教練自助瀏覽仍**無任何 controller 呼叫**，無法建立或瀏覽私教課；但購買流程（`POST /api/orders/direct`）已可用，資料只能手動進 DB。2026-08-19 起已補上 ADMIN 下架／重新上架／依教練查詢三個端點（見 [8.10](#810-admin-私教課下架重新上架adminprivatecoursecontroller)），僅用於解除教練停用的阻塞，不是完整後台管理 |
| 會員管理前端 | [2.6](#26-memberprofilecontrollerapimemberprofile)／[2.7](#27-adminmembercontrollerapiadminmembers-admin) 的 API 已可用，但**尚無對應頁面** |

### 15.2 已修復（保留紀錄供回溯）

| 原問題 | 現況 |
|---|---|
| `GroupCourseController` 是空類別 | 已刪除，改由 `AdminGroupCourseController` 承擔（見 [10.1](#101-團課主檔apiadmingroup-courses-admin)） |
| `spring.profiles.active=dev` 預設全放行 | 已改為預設註解掉，正式安全設定生效；要回到免登入模式取消註解即可 |
| 教練時段與團課列表被擋成 401 | SecurityConfig 已補上對應 permitAll（逐條精確列出，見 [1.5](#15-路徑層級授權 securityconfig)） |
| 欄位驗證失敗一律回統一字串 | 已改為帶出規則自己的訊息並附 `errors`（見 [1.6](#16-錯誤格式)） |
| `cart_item` 主鍵遷移需重啟兩次 | 已移至 `PreSchemaFixupRunner`，在 Hibernate 建表前執行，一次啟動完成 |
| 前台 `/courses` 是空殼 | 已實作為**團課**介紹頁，接團課主檔公開端點 `GET /api/group-courses`（分頁＋搜尋）；私教課仍未接（見 15.1） |

---

## 16. 快速上手

```bash
# 1. 登入取得 token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@wpgym.local","password":"Admin@12345"}'

# 2. 帶 token 呼叫需登入的 API
curl http://localhost:8080/api/orders \
  -H "Authorization: Bearer <accessToken>"

# 3. 免登入端點可直接呼叫
curl "http://localhost:8080/api/products?page=0&size=10"
```

> 種子管理員帳密來自 `app.seed.admin-email`／`app.seed.admin-password`，預設值已進版控，正式環境務必以環境變數覆寫。
