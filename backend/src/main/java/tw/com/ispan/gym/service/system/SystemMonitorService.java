package tw.com.ispan.gym.service.system;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tw.com.ispan.gym.dto.system.SystemMetricsResponse;
import tw.com.ispan.gym.dto.system.SystemMetricsResponse.EndpointStat;

/**
 * 系統監控：從 Micrometer MeterRegistry 讀取 actuator 自動蒐集的指標並彙總。
 * 主埠不開放 /actuator/** web 端點，統一經此處供後台讀取（見 application.properties 的說明）。
 * 指標名稱是 Micrometer 的標準命名（jvm.*、system.*、hikaricp.*、http.server.requests）。
 */
@Service
public class SystemMonitorService {

    /** 監控頁自己的輪詢端點，從「流量前五名」剔除——它每 5 秒打一次，永遠霸榜卻沒有資訊量 */
    private static final String SELF_URI = "/api/admin/system/metrics";

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;

    public SystemMonitorService(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    public SystemMetricsResponse currentMetrics() {
        // DB 健康：直接 ping 一次量延遲，比讀 health indicator 多帶回一個可展示的數字
        String dbStatus = "UP";
        Long dbPing = null;
        try {
            long begin = System.nanoTime();
            jdbc.queryForObject("SELECT 1", Integer.class);
            dbPing = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        } catch (Exception e) {
            dbStatus = "DOWN";
        }

        // process.start.time 是 epoch 秒的 gauge；沒有值時（理論上不會）以現在時間頂替
        double startEpoch = gaugeValue("process.start.time", 0);
        LocalDateTime startedAt = startEpoch > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond((long) startEpoch), ZoneId.systemDefault())
                : LocalDateTime.now();

        // http.server.requests 是多組 tag 各一個 timer，總量與平均延遲要自行加總。
        // 分位數不能「各 timer 的 p95 再平均」（數學上不成立），要把所有 timer 的直方圖
        // bucket 加總成一份，再對彙總後的分布插值——與 PromQL histogram_quantile 同款算法
        long httpCount = 0;
        double httpTotalMillis = 0;
        long http5xx = 0;
        NavigableMap<Double, Double> buckets = new TreeMap<>(); // bucket 上界(ms) -> 累積筆數
        Map<String, double[]> byUri = new HashMap<>(); // uri -> [筆數, 總毫秒]
        for (Timer timer : registry.find("http.server.requests").timers()) {
            long count = timer.count();
            double totalMillis = timer.totalTime(TimeUnit.MILLISECONDS);
            httpCount += count;
            httpTotalMillis += totalMillis;

            String status = timer.getId().getTag("status");
            if (status != null && status.startsWith("5")) {
                http5xx += count;
            }

            // 未開 percentiles-histogram 時 histogramCounts() 是空陣列，分位數會回 0
            for (CountAtBucket bucket : timer.takeSnapshot().histogramCounts()) {
                buckets.merge(bucket.bucket(TimeUnit.MILLISECONDS), bucket.count(), Double::sum);
            }

            String uri = timer.getId().getTag("uri");
            if (uri != null && !SELF_URI.equals(uri)) {
                double[] agg = byUri.computeIfAbsent(uri, k -> new double[2]);
                agg[0] += count;
                agg[1] += totalMillis;
            }
        }

        List<EndpointStat> topEndpoints = byUri.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(5)
                .map(e -> new EndpointStat(e.getKey(), (long) e.getValue()[0],
                        e.getValue()[0] > 0 ? e.getValue()[1] / e.getValue()[0] : 0))
                .toList();

        return new SystemMetricsResponse(
                dbStatus,
                dbPing,
                (long) gaugeValue("process.uptime", 0),
                startedAt,
                (long) sumGauges("jvm.memory.used", "area", "heap"),
                (long) sumGauges("jvm.memory.max", "area", "heap"),
                (int) gaugeValue("jvm.threads.live", 0),
                gaugeValue("system.cpu.usage", 0),
                gaugeValue("process.cpu.usage", 0),
                (int) gaugeValue("hikaricp.connections.active", 0),
                (int) gaugeValue("hikaricp.connections.idle", 0),
                (int) gaugeValue("hikaricp.connections.max", 0),
                httpCount,
                httpCount > 0 ? httpTotalMillis / httpCount : 0,
                quantile(buckets, 0.50),
                quantile(buckets, 0.95),
                quantile(buckets, 0.99),
                http5xx,
                topEndpoints);
    }

    /**
     * 對彙總後的累積直方圖插值出分位數（毫秒）。
     * bucket 記的是「延遲 <= 上界」的累積筆數；找到第一個涵蓋目標排位的 bucket，
     * 在桶內做線性插值。最末桶上界是 Double.MAX_VALUE，插值會得出天文數字，直接回前一個邊界。
     */
    private double quantile(NavigableMap<Double, Double> buckets, double q) {
        if (buckets.isEmpty()) {
            return 0;
        }
        double total = buckets.lastEntry().getValue();
        if (total <= 0) {
            return 0;
        }
        double target = q * total;
        double prevBound = 0;
        double prevCum = 0;
        for (Map.Entry<Double, Double> e : buckets.entrySet()) {
            double bound = e.getKey();
            double cum = e.getValue();
            if (cum >= target) {
                if (cum == prevCum || bound >= Double.MAX_VALUE / 2) {
                    return prevBound;
                }
                return prevBound + (bound - prevBound) * (target - prevCum) / (cum - prevCum);
            }
            prevBound = bound;
            prevCum = cum;
        }
        return prevBound;
    }

    /** 讀單一 gauge；不存在（如尚未註冊）時回預設值，不讓監控本身把頁面弄掛 */
    private double gaugeValue(String name, double fallback) {
        Gauge gauge = registry.find(name).gauge();
        double v = gauge == null ? fallback : gauge.value();
        return Double.isNaN(v) ? fallback : v;
    }

    /** heap 分多個 memory pool（各一個 gauge），依 tag 篩選後加總；max 為 -1（無上限）的不計 */
    private double sumGauges(String name, String tagKey, String tagValue) {
        double sum = 0;
        for (Gauge gauge : registry.find(name).tag(tagKey, tagValue).gauges()) {
            double v = gauge.value();
            if (!Double.isNaN(v) && v > 0) {
                sum += v;
            }
        }
        return sum;
    }
}
