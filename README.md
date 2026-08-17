# ispan-proj-gym

健身房管理系統 —— iSpan 專題（EEIT219）。涵蓋會員與員工帳號、商品購物車與訂單、綠界金流、訂閱制、私人教練預約與團體課程、優惠券、營運報表，以及 AI 健康顧問（InBody 紀錄、AI 課表生成、即時聊天室）。

## 技術棧

| 層 | 技術 |
|---|---|
| 後端 | Spring Boot 4.1 / Java 25 / Spring Security（JWT）/ Spring Data JPA / WebSocket（STOMP over SockJS） |
| 前端 | Vue 3 / Vite / Pinia / Vue Router / Axios |
| 資料庫 | SQL Server 2022（`ispan-proj-gymdb`） |
| 外部服務 | 綠界 ECPay（測試環境）/ Google Gemini（AI 課表與聊天）/ Gmail SMTP（驗證信） |

## 專案結構

```
ispan-proj-gym/
├── backend/                 # Spring Boot 後端（Maven）
├── frontend/ispan-proj-gym/ # Vue 3 前端（Vite）
└── docs/                    # 詳細文件
```

## 啟動方式

### 前置需求

- JDK 25、Node.js 20+
- SQL Server 於 `localhost:1433`，建立資料庫 `ispan-proj-gymdb`（schema 由 `ddl-auto=update` 自動建立）

### 後端（port 8080）

```bash
cd backend
./mvnw spring-boot:run        # Windows 用 mvnw.cmd
```

- 預設以 **dev profile** 啟動：所有 API 免驗證、`@PreAuthorize` 不生效，登入身分固定為 `app.dev-current-user-id`。要驗證真實權限，設環境變數 `SPRING_PROFILES_ACTIVE=prod` 或註解掉 `application.properties` 的 `spring.profiles.active=dev`
- 啟動時自動建立種子管理員（`app.seed.admin-email` / `app.seed.admin-password`，預設 `admin@wpgym.local` / `Admin@12345`）
- 執行測試：`./mvnw test`

### 前端（port 5173）

```bash
cd frontend/ispan-proj-gym
npm install
npm run dev
```

`/api` 請求由 Vite 代理至 `localhost:8080`，無須另外設定。

## 文件

| 文件 | 內容 |
|---|---|
| [API Reference](docs/API-REFERENCE.md) | 全部 REST 端點與 WebSocket 聊天室契約：路徑、權限、請求／回應欄位、錯誤碼 |
| ER Model / ER Diagram | 資料表定義與實體關聯圖，屬內部設計文件不進版控（`docs/` 本機），需要請向隊員索取 |

## 部署注意事項

正式環境必須以環境變數覆寫下列已進版控的本機預設值，並關閉 `ddl-auto`：

`JWT_SECRET`、`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`、`SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD`、`ECPAY_*`、`MAIL_USERNAME` / `MAIL_PASSWORD`、`GOOGLE_API_KEY`
