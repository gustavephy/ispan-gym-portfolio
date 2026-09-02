# bi — 自助式 BI（Metabase）

跟後台自製報表頁並排展示用：固定報表之外，營運人員可以在 Metabase 自己拖拉出新報表，
不用開票給工程師。Demo 用直連生產 SQL Server；業界正式環境接的是唯讀副本或資料倉儲。

## 啟動

```bash
cd bi
docker compose up -d
```

- Metabase：http://localhost:3001
- 管理員帳號：`admin@local.test`／`Gym@Admin12345`（本機 Demo 用，非機密）
- 儀表板「營運總覽」與資料庫連線設定存在 `metabase-data` volume，`docker compose down` 不會消失
  （`docker compose down -v` 才會清掉，要重來時用）

## 首次建置（已做過就不用）

初始設定（建管理員、接 SQL Server、建圖卡與「營運總覽」儀表板、開 public link）
由 `bi/metabase_setup.py` 完成；重建時在 repo 根目錄：

```bash
set -a && . ./.env && set +a && python bi/metabase_setup.py
```

要點：
- SQL Server 連線用 `host.docker.internal:1433`，自簽憑證要在 additional options 加
  `trustServerCertificate=true`
- 資料庫存的 datetime 是 UTC 牆鐘（`hibernate.jdbc.time_zone=UTC`），
  **圖卡 SQL 的日期一律 `DATEADD(HOUR, 8, ...)` 轉台北再分桶**，
  跟後端 OperationReportService 的 Java 端分桶是同一個口徑
- 儀表板頂端掛了**日期選擇器**（field filter），未設定＝開站以來；
  注意 filter 比的是 DB 的 UTC 牆鐘，日界有 8 小時偏移（Demo 可接受，
  正宗解法是倉儲層建台北時區的日期維度）。field filter 綁的表在 SQL 裡
  **不能取別名**（Metabase 以原表名注入條件）
- 後台「營運分析」頁嵌的是 public link 的 iframe（無認證，本機 Demo 可接受；
  正式環境要換 signed embedding——後端用 embed secret 簽 JWT）；public link 換了要同步改
  `AdminAnalyticsView.vue` 的網址常數

## Demo 講點

| 自製報表頁 | Metabase |
|---|---|
| 固定口徑、固定圖表，改一張圖要走開發循環 | 營運自己拖拉，工程師退到維護資料與口徑 |
| 快照式，數字凍結可對帳 | 即時查詢，探索用 |

兩者互補：對帳走快照、探索走 BI——業界也是這樣分工的。
