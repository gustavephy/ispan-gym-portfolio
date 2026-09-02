package tw.com.ispan.gym.dto.system;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系統監控指標彙總（後台儀表板用，每次請求即時讀取）。
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
        List<EndpointStat> topEndpoints) {

    /** 依 uri 彙總的端點流量統計（監控頁「流量前五名」表格用） */
    public record EndpointStat(String uri, long count, double avgMillis) {
    }
}
