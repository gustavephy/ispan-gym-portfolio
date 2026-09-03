# ispan-proj-gym

健身房管理系統 —— iSpan 專題（EEIT219）。涵蓋會員與員工帳號、商品購物車與訂單、綠界金流、訂閱制、私人教練預約與團體課程、優惠券、營運報表與 BI、系統監控，以及 AI 健康顧問（InBody 紀錄、AI 課表生成、即時聊天室）。

## 技術棧

| 層 | 技術 |
|---|---|
| 後端 | Spring Boot 4.1 / Java 25 / Spring Security（JWT）/ Spring Data JPA / WebSocket（STOMP over SockJS） |
| 前端 | Vue 3 / Vite / Pinia / Vue Router / Axios / ECharts |
| 資料庫 | SQL Server 2022（`ispan-proj-gymdb`） |
| 測試 | JUnit 5 ＋ Mockito（1300+ 後端測試）/ Playwright E2E（~150 案例，`frontend/e2e/`） |
| 可觀測性 | Micrometer → Prometheus ＋ Grafana（`monitoring/`）；後台另有自製監控頁 |
| BI | Metabase（`bi/`，後台「營運分析」頁嵌入公開儀表板） |
| 部署 | Docker Compose 全容器一鍵啟動（含資料庫、監控、BI 全自動，見 `DEPLOY.md`） |
| 外部服務 | 綠界 ECPay（測試環境）/ Google Gemini（AI 課表與聊天）/ Gmail SMTP（驗證信） |

## 專案結構

```
ispan-proj-gym/
├── backend/                 # Spring Boot 後端（Maven）
├── frontend/                # Vue 3 前端（Vite）；E2E 在 frontend/e2e/
├── monitoring/              # Prometheus + Grafana（docker compose，儀表板自動佈建）
├── bi/                      # Metabase 自助式 BI（docker compose + 自動建置腳本）
├── docker-compose.yml       # 全容器一鍵啟動（含 SQL Server；開發模式與此擇一）
├── DEPLOY.md                # 換機部署 runbook（全容器三步 / 開發模式完整步驟）
└── docs/                    # 詳細文件
```

## 啟動方式（開發模式）

### 前置需求

- JDK 25、Node.js 20+、Docker Desktop（跑 monitoring／bi 用）
- SQL Server 於 `localhost:1433`，建立空資料庫 `ispan-proj-gymdb`（表結構與種子資料由首次啟動自動建立）
- **repo 根目錄要有 `.env`**（DB 密碼等機密，不進版控——跟隊員索取）

### 後端（port 8080）

```bash
cd backend
set -a && . ../.env && set +a && ./mvnw spring-boot:run    # Git Bash；要先載入 .env
```

- **預設即完整 JWT 驗證**（`spring.profiles.active=dev` 已預設註解）。要本機免驗證測試才打開 dev profile——所有 API 免驗證、登入身分固定為 `app.dev-current-user-id`
- 首次啟動自動播種：始祖管理員、測試帳號（密碼一律 `Admin@12345`，見登入頁測試帳號面板）、商品／教練／課程／擬真會員與樣本訂單（統計圖表開箱即有資料）
- 執行測試：`./mvnw clean test`（測試期間後端不可同時在跑，共用 `target/`）

### 前端（port 5173）

```bash
cd frontend
npm install
npm run dev
```

`/api` 請求由 Vite 代理至 `localhost:8080`，無須另外設定。

### 監控與 BI（可選）

```bash
cd monitoring && docker compose up -d   # Grafana :3000（免登入）、Prometheus :9090
cd bi && docker compose up -d           # Metabase :3001；首次要跑 python bi/metabase_setup.py
```

## 全容器一鍵啟動（部署／驗收用）

```bash
docker compose up -d --build   # 資料庫、後端、前端、監控、BI 全包，首次全自動建庫播種
```

與開發模式**擇一**（埠與容器名會撞）。完整說明、換機部署與常見坑見 [DEPLOY.md](DEPLOY.md)。

## 文件

| 文件 | 內容 |
|---|---|
| [API Reference](docs/API-REFERENCE.md) | 全部 REST 端點與 WebSocket 聊天室契約：路徑、權限、請求／回應欄位、錯誤碼 |
| [DEPLOY.md](DEPLOY.md) | 換機部署 runbook（全容器三步／開發模式）、驗收清單、常見坑 |
| ER Model / ER Diagram | 資料表定義與實體關聯圖，屬內部設計文件不進版控（`docs/` 本機），需要請向隊員索取 |

## 部署注意事項

正式環境必須以環境變數覆寫下列已進版控的本機預設值，並關閉 `ddl-auto`：

`JWT_SECRET`、`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`、`SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD`、`ECPAY_*`、`MAIL_USERNAME` / `MAIL_PASSWORD`、`GOOGLE_API_KEY`；並設 `SEED_TEST_DATA_ENABLED=false`（測試帳號密碼是公開已知的）。
