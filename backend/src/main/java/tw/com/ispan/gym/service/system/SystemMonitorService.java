package tw.com.ispan.gym.service.system;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tw.com.ispan.gym.dto.system.SystemMetricsResponse;

/**
 * 系統監控：從 Micrometer MeterRegistry 讀取 actuator 自動蒐集的指標並彙總。
 * 刻意不開放 /actuator/** web 端點，統一經此處供後台讀取（見 application.properties 的說明）。
 * 指標名稱是 Micrometer 的標準命名（jvm.*、system.*、hikaricp.*、http.server.requests）。
 */
@Service
public class SystemMonitorService {

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

        // http.server.requests 是多組 tag 各一個 timer，總量與平均延遲要自行加總
        long httpCount = 0;
        double httpTotalMillis = 0;
        for (Timer timer : registry.find("http.server.requests").timers()) {
            httpCount += timer.count();
            httpTotalMillis += timer.totalTime(TimeUnit.MILLISECONDS);
        }

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
                httpCount > 0 ? httpTotalMillis / httpCount : 0);
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
