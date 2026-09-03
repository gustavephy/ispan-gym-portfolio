# Demo 部署 Runbook（在另一台電腦從零起機）

兩種模式，**擇一**（埠與 container 名會互撞，不要混跑）：

| | A. 全容器模式（新機器推薦） | B. 開發模式（本機彩排用的組合） |
|---|---|---|
| 要裝的東西 | 只要 Docker Desktop | JDK 25、Node、Python、SQL Server、Docker |
| 資料庫 | 容器內（獨立資料，首次自動建庫播種） | 宿主機 SQL Server |
| 埠 | 前端 5173／後端 8088／DB 14333 | 前端 5173／後端 8080／DB 1433 |
| 適用 | 換機部署、一鍵展示 | 平常開發、E2E |

> **黃金原則：Demo 前一天在目標機完整跑通一次。** 首次要拉 ~3.5GB 映像檔、
> 容器內抓 Maven 依賴，全都吃網路——當天不要賭現場 Wi-Fi。

---

## 模式 A：全容器（三步）

**0. 出發前（原機器）**：程式碼推上 GitHub 或 `git bundle create /e/gym.bundle dev`；
`cp .env /e/gym.env`（機密不在 git 裡，一定要帶）。

**1. 目標機裝 Docker Desktop**——先進 BIOS 開虛擬化（Intel VT-x／AMD SVM），
不然 WSL2 引擎起不來。

**2. 拿碼＋放機密**：
```bash
git clone <repo 或 bundle> ispan-proj-gym && cd ispan-proj-gym
cp /e/gym.env .env
```

**3. 一鍵啟動**：
```bash
docker compose up -d --build
```
首次啟動的自動化鏈（約 5–10 分鐘）：SQL Server 容器健康 → 自動建庫 →
後端建表＋播種（商品/教練/擬真會員/訂單/場次全自動）→ Metabase 自動建好
BI 儀表板並把 public 網址交給前端。**零手動設定。**

進度看 `docker compose logs -f backend`（出現 `Started BackendApplication` 即就緒）、
`docker compose logs metabase-init`（結尾印 PUBLIC_URL 即完成）。

入口：前端 http://localhost:5173 ・ Grafana :3000 ・ Metabase :3001 ・
Prometheus :9090 ・ 後端 API :8088 ・ 容器 DB（SSMS 觀察用）localhost,14333

---

## 模式 B：開發模式（與平常本機相同）

<details>
<summary>展開完整步驟</summary>

1. 安裝：Git、Node 20+、JDK 25、Python 3、SQL Server（混合驗證、sa 密碼設成跟
   .env 一樣、TCP 1433、服務自動啟動）、Docker Desktop
2. 拿碼＋`.env`（同模式 A 步驟 0、2）
3. 建空庫：`sqlcmd -S localhost,1433 -U sa -P "<密碼>" -C -Q "CREATE DATABASE [ispan-proj-gymdb]"`
4. 啟動（Git Bash，各開一個終端）：
   ```bash
   cd backend && set -a && . ../.env && set +a && ./mvnw spring-boot:run  # 等 Started
   cd frontend && npm ci && npm run dev
   cd monitoring && docker compose up -d
   cd bi && docker compose up -d
   ```
5. Metabase 首次建置（它的狀態在 volume、不跟 git 走）：
   ```bash
   set -a && . ./.env && set +a && python bi/metabase_setup.py
   ```
   腳本會自動把 public 網址寫進 `frontend/public/mbconfig/url.txt`，前端執行期自己讀，
   不用改程式碼。

</details>

---

## 驗收清單（兩種模式通用，Demo 前跑一遍）

- [ ] 前台商品頁**圖片有出來**（Cloudinary 外連，需網路）
- [ ] 管理端登入 `emp-admin@local.test`／`Admin@12345`
- [ ] 營運統計：四張即時圖有資料；產生報表 → 再生成 → 版本選單出現
- [ ] 營運分析：Metabase 儀表板顯示、日期選擇器能切
- [ ] 系統監控：折線從右緣長出來；F5 歷史不消失
- [ ] Grafana :3000 有畫面（Prometheus Targets 兩個目標會有一個 down——
      對應另一種跑法，正常）
- [ ] 聊天室能開（WebSocket）

## 常見坑

| 症狀 | 原因／解法 |
|---|---|
| Docker 引擎起不來 | BIOS 虛擬化沒開 |
| compose 報 DB_PASSWORD 未設定 | `.env` 沒放到 repo 根目錄 |
| 拉映像報 `TLS handshake timeout` | 該網路連不上 Docker Hub——見下方「Docker Hub 連不上」一節 |
| 容器名衝突（gym-prometheus 等） | A/B 模式混跑了——把另一邊 down 掉 |
| 容器名衝突（gym-docker-* 等） | 上次部署失敗的殘骸——見「Docker Hub 連不上」節末的清理指令 |
| 5173 被占 | 同上：Vite dev 與前端容器只能擇一 |
| 登入一直 429 | 登入限流（同 IP 30 次/時），重啟後端歸零 |
| 商品沒圖 | 現場沒網路（Cloudinary 是外連） |
| 營運分析空白 | Metabase 還在初始化，看 `docker compose logs metabase-init` |
| 模式 B 後端連線被拒 | MSSQLSERVER 服務沒起來，服務管理員手動開 |

## Docker Hub 連不上（TLS handshake timeout）

有些網路（實測過某些家用 ISP；會場 Wi-Fi 風險更高）連不上 `registry-1.docker.io`，
症狀是拉映像卡住後報 `net/http: TLS handshake timeout`——MCR（SQL Server）與
GHCR（GraalVM）通常不受影響，只有 Docker Hub 的映像（node/nginx/python/grafana/
prometheus/metabase）會炸。先用一行確認 Google 鏡像站通不通：

```bash
curl -s -o /dev/null -w "%{http_code}" -m 15 https://mirror.gcr.io/v2/   # 回 401 = 通
```

**解法一（首選，一次設定永久有效）**：Docker Desktop → Settings → Docker Engine，
JSON 加 `"registry-mirrors": ["https://mirror.gcr.io"]` → Apply & restart。
之後所有 Docker Hub 拉取自動走鏡像。注意重啟會停掉運行中的容器，
之後要 `docker compose up -d` 帶回來。

**解法二（不動設定，逐個手拉）**：從鏡像站拉、改回原名；官方映像
（名稱沒有斜線的）要補 `library/` 前綴：

```bash
docker pull mirror.gcr.io/library/node:20-alpine && docker tag mirror.gcr.io/library/node:20-alpine node:20-alpine
docker pull mirror.gcr.io/grafana/grafana:12.1.0 && docker tag mirror.gcr.io/grafana/grafana:12.1.0 grafana/grafana:12.1.0
# python:3.12-alpine、nginx:alpine、prom/prometheus、metabase/metabase 同理
```

本機有同名映像後 compose 就不會再去 Docker Hub。

**解法三（現場完全沒網路的保險）**：前一天在能動的機器打包、現場載入：

```bash
docker save -o gym-images.tar ispan-proj-gym-backend ispan-proj-gym-frontend \
  mcr.microsoft.com/mssql/server:2022-latest prom/prometheus:v3.5.0 \
  grafana/grafana:12.1.0 metabase/metabase:latest python:3.12-alpine
# 目標機：
docker load -i gym-images.tar && docker compose up -d
```

**失敗過一次後重跑**，常撞 `container name already in use`（上次的殘骸）：

```bash
docker ps -a --filter "name=gym-" -q | xargs docker rm -f
docker compose up -d
```

## Demo 當天（開場前 15 分鐘）

模式 A：開 Docker Desktop → `docker compose up -d`（資料都在 volume，秒起）。
模式 B：確認 MSSQLSERVER 服務 → 四組啟動指令 → 快速點驗收清單前四項。
兩種模式都建議把「系統監控」頁提前開著，讓 5 分鐘折線在上台前畫滿。
