package tw.com.ispan.gym.service.system;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tw.com.ispan.gym.dto.system.SystemMetricsResponse;
import tw.com.ispan.gym.dto.system.SystemMetricsResponse.EndpointStat;
import tw.com.ispan.gym.dto.system.SystemMetricsResponse.SamplePoint;

/**
 * 系統監控：從 Micrometer MeterRegistry 讀取 actuator 自動蒐集的指標並彙總。
 * 主埠不開放 /actuator/** web 端點，統一經此處供後台讀取（見 application.properties 的說明）。
 * 指標名稱是 Micrometer 的標準命名（jvm.*、system.*、hikaricp.*、http.server.requests）。
 *
 * <p>折線圖的歷史由後端排程每 5 秒自取樣（{@link #sampleNow()}）進 60 格環形緩衝，
 * 而不是讓瀏覽器自己累積——頁面重新整理、切換路由、多人同看拿到的都是同一份 5 分鐘歷史。
 * 緩衝在記憶體，後端重啟即歸零（與其他即時指標同壽命）。
 */
@Service
public class SystemMonitorService {

    /** 監控頁自己的輪詢端點，從「流量前五名」剔除——它每 5 秒打一次，永遠霸榜卻沒有資訊量 */
    private static final String SELF_URI = "/api/admin/system/metrics";

    /** 取樣保留筆數：5 秒一筆 × 60 筆 ＝ 最近 5 分鐘 */
    private static final int HISTORY_POINTS = 60;

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;

    /** 環形緩衝與差分基準，皆以 this 為鎖：排程執行緒寫入、請求執行緒讀快照 */
    private final Deque<SamplePoint> history = new ArrayDeque<>();
    private long prevSampleAt;
    private long prevHttpCount;
    private long prevHttp5xx;

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

        HttpAggregate http = aggregateHttp();

        List<EndpointStat> topEndpoints = http.byUri().entrySet().stream()
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
                http.count(),
                http.count() > 0 ? http.totalMillis() / http.count() : 0,
                quantile(http.buckets(), 0.50),
                quantile(http.buckets(), 0.95),
                quantile(http.buckets(), 0.99),
                http.fiveXx(),
                topEndpoints,
                historySnapshot());
    }

    /** 排程進入點：真正的取樣邏輯在 sampleAt，時間戳拆成參數讓測試能控制時鐘 */
    @Scheduled(fixedDelay = 5000)
    public void sampleNow() {
        sampleAt(System.currentTimeMillis());
    }

    synchronized void sampleAt(long now) {
        HttpAggregate http = aggregateHttp();

        // counter 差分 → 速率；後端剛啟動沒有前一筆、或計數歸零（理論上不會）時速率記 0
        double reqPerSec = 0;
        double errRatePct = 0;
        if (prevSampleAt > 0 && now > prevSampleAt) {
            long dCount = http.count() - prevHttpCount;
            long dFiveXx = http.fiveXx() - prevHttp5xx;
            if (dCount > 0) {
                reqPerSec = dCount * 1000.0 / (now - prevSampleAt);
                errRatePct = dFiveXx > 0 ? dFiveXx * 100.0 / dCount : 0;
            }
        }
        prevSampleAt = now;
        prevHttpCount = http.count();
        prevHttp5xx = http.fiveXx();

        double heapUsed = sumGauges("jvm.memory.used", "area", "heap");
        double heapMax = sumGauges("jvm.memory.max", "area", "heap");
        history.addLast(new SamplePoint(
                now,
                heapMax > 0 ? heapUsed * 100.0 / heapMax : 0,
                gaugeValue("process.cpu.usage", 0) * 100.0,
                gaugeValue("system.cpu.usage", 0) * 100.0,
                (int) gaugeValue("hikaricp.connections.active", 0),
                (int) gaugeValue("hikaricp.connections.idle", 0),
                reqPerSec,
                errRatePct,
                quantile(http.buckets(), 0.50),
                quantile(http.buckets(), 0.95),
                quantile(http.buckets(), 0.99)));
        while (history.size() > HISTORY_POINTS) {
            history.removeFirst();
        }
    }

    private synchronized List<SamplePoint> historySnapshot() {
        return List.copyOf(history);
    }

    /** 一趟掃描 http.server.requests 全部 timer 的彙總結果；byUri 的值是 [筆數, 總毫秒] */
    private record HttpAggregate(long count, double totalMillis, long fiveXx,
            NavigableMap<Double, Double> buckets, Map<String, double[]> byUri) {
    }

    /**
     * http.server.requests 是多組 tag 各一個 timer，總量與平均延遲要自行加總。
     * 分位數不能「各 timer 的 p95 再平均」（數學上不成立），要把所有 timer 的直方圖
     * bucket 加總成一份，再對彙總後的分布插值——與 PromQL histogram_quantile 同款算法。
     */
    private HttpAggregate aggregateHttp() {
        long count = 0;
        double totalMillis = 0;
        long fiveXx = 0;
        NavigableMap<Double, Double> buckets = new TreeMap<>(); // bucket 上界(ms) -> 累積筆數
        Map<String, double[]> byUri = new HashMap<>();
        for (Timer timer : registry.find("http.server.requests").timers()) {
            long timerCount = timer.count();
            double timerMillis = timer.totalTime(TimeUnit.MILLISECONDS);
            count += timerCount;
            totalMillis += timerMillis;

            String status = timer.getId().getTag("status");
            if (status != null && status.startsWith("5")) {
                fiveXx += timerCount;
            }

            // 未開 percentiles-histogram 時 histogramCounts() 是空陣列，分位數會回 0
            for (CountAtBucket bucket : timer.takeSnapshot().histogramCounts()) {
                buckets.merge(bucket.bucket(TimeUnit.MILLISECONDS), bucket.count(), Double::sum);
            }

            String uri = timer.getId().getTag("uri");
            if (uri != null && !SELF_URI.equals(uri)) {
                double[] agg = byUri.computeIfAbsent(uri, k -> new double[2]);
                agg[0] += timerCount;
                agg[1] += timerMillis;
            }
        }
        return new HttpAggregate(count, totalMillis, fiveXx, buckets, byUri);
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
