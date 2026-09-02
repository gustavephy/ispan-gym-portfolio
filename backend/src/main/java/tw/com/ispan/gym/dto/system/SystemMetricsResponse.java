package tw.com.ispan.gym.dto.system;

import java.time.LocalDateTime;

/**
 * 系統監控指標彙總（後台儀表板用，每次請求即時讀取）。
 * 來源：Micrometer MeterRegistry（JVM/CPU/連線池/HTTP）＋ 直接對資料庫 ping。
 * CPU 使用率為 0–1 的比例；heap 與延遲單位見欄位名。
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
        double httpAvgLatencyMillis) {
}
