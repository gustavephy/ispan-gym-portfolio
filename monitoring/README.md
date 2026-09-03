# monitoring — 業界版監控管線（Prometheus + Grafana）

跟後台自製的「系統監控」頁（`/admin/system`）並排展示用：同一份 Micrometer 指標來源，
一邊是自製輕量儀表，一邊是業界標準鏈路。

```
後端(8081 管理埠) --/actuator/prometheus--> Prometheus(9090, 每 15 秒拉+落地) --> Grafana(3000, 畫圖)
```

## 啟動

1. 後端照常啟動（指標端點在管理埠 8081，主埠 8080 沒有任何 /actuator/**）
2. Docker Desktop 開著，然後：

```bash
cd monitoring
docker compose up -d
```

- Grafana：http://localhost:3000 （Demo 設定為免登入，儀表板「Gym Backend 監控（業界版）」已自動載入）
- Prometheus：http://localhost:9090 （Status → Targets 可看抓取狀態）

關閉：`docker compose down`（時序資料未掛 volume，關掉即清空——Demo 用途）。

## 跟自製監控頁的差異（Demo 講點）

| 自製 /admin/system | 這套 |
|---|---|
| 資料存瀏覽器記憶體，5 分鐘視窗，F5 歸零 | Prometheus 時序庫落地，可回看任意時間段 |
| 累計平均延遲 | p50/p95/p99 直方圖分位數 |
| 只有總請求數 | req/s 速率、依端點分組、5xx 錯誤率 |
| 自寫端點＋自畫圖 | 標準 /actuator/prometheus ＋ Grafana，應用端零客製 |

兩邊指標來源都是 Micrometer——所以升級路徑是平滑的，這是當初選型的重點。
