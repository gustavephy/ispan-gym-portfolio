package tw.com.ispan.gym.dto.system;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系統監控指標彙總（後台儀表板用）。
 * 即時欄位每次請求即時讀取；history 是後端排程每 5 秒自取樣的最近 5 分鐘（60 筆），
 * 折線圖畫的是它——取樣在伺服器端做，頁面重新整理／多人同看拿到的是同一份歷史。
 * 來源：Micrometer MeterRegistry（JVM/CPU/連線池/HTTP）＋ 直接對資料庫 ping。
 * CPU 使用率為 0–1 的比例；heap 與延遲單位見欄位名。
 * 分位數（p50/p95/p99）由各 timer 的直方圖 bucket 彙總後插值，見 SystemMonitorService。
 */
public record SystemMetricsResponse(
        String dbStatus,
        Long dbPingMillis,
        long uptimeSeconds,
        LocalDateTime startedAt,
        long heapUsedBytes,
        long heapMaxBytes,
        int liveThreads,
        double systemCpuUsage,
        double processCpuUsage,
        Integer dbPoolActive,
        Integer dbPoolIdle,
        Integer dbPoolMax,
        long httpRequestCount,
        double httpAvgLatencyMillis,
        double httpP50Millis,
        double httpP95Millis,
        double httpP99Millis,
        long http5xxCount,
        List<EndpointStat> topEndpoints,
        List<SamplePoint> history) {

    /** 依 uri 彙總的端點流量統計（監控頁「流量前五名」表格用） */
    public record EndpointStat(String uri, long count, double avgMillis) {
    }

    /** 一筆排程取樣：百分比欄位已是 0–100，速率與錯誤率由後端對前一筆差分而來 */
    public record SamplePoint(
            long at,
            double heapPct,
            double processCpuPct,
            double systemCpuPct,
            int poolActive,
            int poolIdle,
            double reqPerSec,
            double errRatePct,
            double p50Millis,
            double p95Millis,
            double p99Millis) {
    }
}
