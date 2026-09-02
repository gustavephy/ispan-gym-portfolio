# Demo 部署 Runbook（在另一台電腦從零起機）

目標配置與平常開發相同：**後端 8080（dev 模式）＋前端 5173（Vite）＋ monitoring/ 與 bi/ 容器**。
全容器版（`docker compose up`，後端 8088）當備援展示用，不當 Demo 主線——主線用彩排過的組合。

> **黃金原則：Demo 前一天在目標機完整跑通一次。** 現場網路不可靠，
> Docker 映像檔（~2GB）、npm、Maven 依賴都要在家先抓好。

## 0. 前置安裝（一次性）

- [ ] Git、**Node 20+**、**JDK 25**（後端 dev 模式要；只跑容器版可免）
- [ ] **Python 3**（跑 Metabase 重建腳本用）
- [ ] **SQL Server**（Developer/Express 皆可）：啟用 SQL 驗證、sa 帳號、TCP 1433；
      服務名 MSSQLSERVER 設自動啟動
- [ ] **Docker Desktop**：BIOS 要先開虛擬化（Intel VT-x／AMD SVM，在 Advanced／CPU 設定），
      沒開的話 WSL2 引擎起不來——別問我們怎麼知道的

## 1. 拿程式碼與機密

- [ ] `git clone`（或隨身碟 `git bundle`）拉最新 dev
- [ ] **手動複製根目錄 `.env`**（隨身碟）——它刻意不在 git 裡，含 DB 密碼、信箱、
      Google OAuth、Cloudinary、Gemini 金鑰。沒有它後端起不來

## 2. 建資料庫（一次性）

```bash
# SSMS 或 sqlcmd 建空庫即可，表結構與種子資料由後端首次啟動自動建立
sqlcmd -S localhost,1433 -U sa -P "<密碼>" -C -Q "CREATE DATABASE [ispan-proj-gymdb]"
```

首次啟動後端時 SchemaFixupRunner ＋種子會自動：建表、46 商品（含 Cloudinary 圖）、
12 教練、16 團課、36 擬真會員、60 樣本訂單、過去場次與預約——統計圖表開箱即有資料；
昨日日報也會由啟動補跑自動生成。

## 3. 啟動（每次，Git Bash）

```bash
# 3-1 後端（確認 MSSQLSERVER 服務在跑）
cd backend && set -a && . ../.env && set +a && ./mvnw spring-boot:run
# 就緒判準：log 出現 Started BackendApplication 且 GET /api/products 回 200

# 3-2 前端
cd frontend && npm ci && npm run dev        # http://localhost:5173

# 3-3 監控（Prometheus 9090 + Grafana 3000，儀表板自動佈建）
cd monitoring && docker compose up -d

# 3-4 BI（Metabase 3001）
cd bi && docker compose up -d
```

## 4. Metabase 首次設定（換機器必做）

Metabase 的儀表板存在 docker volume 裡，**不會跟著 git 走**，新機器要重建：

```bash
# repo 根目錄；腳本會等它初始化、建管理員、接 SQL Server、建卡與儀表板、開 public link
set -a && . ./.env && set +a && python bi/metabase_setup.py
```

結尾會印出 `PUBLIC_URL=...`。**把該網址的 UUID 換進
`frontend/src/views/admin/AdminAnalyticsView.vue` 的 `PUBLIC_DASHBOARD_URL`**（Vite 會熱更新）
——public link 是各機器自己的，不換的話「營運分析」頁會是空框。

## 5. 驗收清單（Demo 前跑一遍）

- [ ] 前台：商品頁**圖片有出來**（Cloudinary 外連，需要網路）
- [ ] 管理端登入 `emp-admin@local.test`／`Admin@12345`
- [ ] 營運統計：四張即時圖有資料；產生一份報表 → 再生成一次 → 版本選單出現
- [ ] 營運分析：Metabase 儀表板顯示、日期選擇器能切
- [ ] 系統監控：折線圖從右緣長出來；F5 歷史不消失
- [ ] Grafana http://localhost:3000 儀表板有畫面
- [ ] 聊天室能開（WebSocket）

## 6. 常見坑

| 症狀 | 原因／解法 |
|---|---|
| 後端連線被拒 | MSSQLSERVER 服務沒起來，服務管理員手動開 |
| Docker 引擎起不來 | BIOS 虛擬化沒開 |
| 登入一直 429 | 登入限流（同 IP 30 次/時），重啟後端歸零 |
| 商品沒圖 | 現場沒網路（Cloudinary 是外連） |
| 營運分析空白 | Metabase 沒起／public UUID 沒換（見步驟 4） |
| Vite 起不來說 5173 被占 | 全容器版和 dev 模式撞埠，二選一 |

## 備援：全容器一鍵版

目標機裝不了 JDK／Node 時的退路（仍需宿主 SQL Server 與 .env）：

```bash
docker compose up -d --build   # 後端 8088、前端 5173（nginx）
```
