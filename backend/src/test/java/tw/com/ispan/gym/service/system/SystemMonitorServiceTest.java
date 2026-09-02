package tw.com.ispan.gym.service.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import tw.com.ispan.gym.dto.system.SystemMetricsResponse;

/**
 * 用真的 SimpleMeterRegistry 註冊假指標，驗證彙總邏輯：
 * heap 多 pool 加總、max=-1 不計、timer 平均延遲、缺指標時回預設值不噴錯。
 */
class SystemMonitorServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SystemMonitorService service = new SystemMonitorService(registry, jdbc);

    @Test
    void heap多個pool加總且max為負一的不計() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        registry.gauge("jvm.memory.used", Tags.of("area", "heap", "id", "eden"), 100.0);
        registry.gauge("jvm.memory.used", Tags.of("area", "heap", "id", "old"), 250.0);
        registry.gauge("jvm.memory.used", Tags.of("area", "nonheap", "id", "meta"), 999.0);
        registry.gauge("jvm.memory.max", Tags.of("area", "heap", "id", "eden"), -1.0);
        registry.gauge("jvm.memory.max", Tags.of("area", "heap", "id", "old"), 4096.0);

        SystemMetricsResponse m = service.currentMetrics();

        assertThat(m.heapUsedBytes()).isEqualTo(350L); // 只加 heap，不含 nonheap
        assertThat(m.heapMaxBytes()).isEqualTo(4096L); // -1（無上限）不計
        assertThat(m.dbStatus()).isEqualTo("UP");
        assertThat(m.dbPingMillis()).isNotNull();
    }

    @Test
    void http請求計時器加總並算平均延遲() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        registry.timer("http.server.requests", "uri", "/api/a").record(100, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests", "uri", "/api/a").record(300, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests", "uri", "/api/b").record(200, TimeUnit.MILLISECONDS);

        SystemMetricsResponse m = service.currentMetrics();

        assertThat(m.httpRequestCount()).isEqualTo(3L);
        assertThat(m.httpAvgLatencyMillis()).isEqualTo(200.0); // (100+300+200)/3
    }

    @Test
    void 分位數由彙總直方圖插值而來且高分位不低於低分位() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        // publishPercentileHistogram 只對 Prometheus 型 registry 發布 bucket，
        // SimpleMeterRegistry 會忽略；單元測試改用明確 SLO bucket（任何 registry 都發布）
        // 驗證同一條「bucket 彙總＋桶內插值」路徑。正式環境的 bucket 由
        // application.properties 的 percentiles-histogram 設定產生。
        Timer timer = Timer.builder("http.server.requests").tag("uri", "/api/a")
                .serviceLevelObjectives(java.time.Duration.ofMillis(25), java.time.Duration.ofMillis(50),
                        java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(1000))
                .register(registry);
        for (int i = 0; i < 95; i++) {
            timer.record(20, TimeUnit.MILLISECONDS);
        }
        for (int i = 0; i < 5; i++) {
            timer.record(800, TimeUnit.MILLISECONDS);
        }

        SystemMetricsResponse m = service.currentMetrics();

        // 95 筆落在 <=25ms 桶、5 筆落在 <=1000ms 桶：
        // p50 在首桶內插值（約 13ms）、p99 在 500–1000ms 桶內插值（約 900ms）
        assertThat(m.httpP50Millis()).isBetween(5.0, 25.0);
        assertThat(m.httpP99Millis()).isBetween(500.0, 1000.0);
        assertThat(m.httpP95Millis()).isGreaterThanOrEqualTo(m.httpP50Millis());
        assertThat(m.httpP99Millis()).isGreaterThanOrEqualTo(m.httpP95Millis());
    }

    @Test
    void 未開直方圖時分位數回零不噴錯() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        registry.timer("http.server.requests", "uri", "/api/a").record(100, TimeUnit.MILLISECONDS);

        SystemMetricsResponse m = service.currentMetrics();

        assertThat(m.httpP95Millis()).isZero();
    }

    @Test
    void 前五端點依請求數排序且剔除監控端點自身() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        for (int i = 0; i < 3; i++) {
            registry.timer("http.server.requests", "uri", "/api/a").record(100, TimeUnit.MILLISECONDS);
        }
        for (int i = 0; i < 5; i++) {
            registry.timer("http.server.requests", "uri", "/api/b").record(50, TimeUnit.MILLISECONDS);
        }
        for (int i = 0; i < 99; i++) {
            registry.timer("http.server.requests", "uri", "/api/admin/system/metrics").record(1, TimeUnit.MILLISECONDS);
        }

        SystemMetricsResponse m = service.currentMetrics();

        assertThat(m.topEndpoints()).extracting(SystemMetricsResponse.EndpointStat::uri)
                .containsExactly("/api/b", "/api/a"); // 自身輪詢量最大但不上榜
        assertThat(m.topEndpoints().get(0).count()).isEqualTo(5L);
        assertThat(m.topEndpoints().get(0).avgMillis()).isEqualTo(50.0);
        // 剔除只影響排行榜，總量照算
        assertThat(m.httpRequestCount()).isEqualTo(107L);
    }

    @Test
    void 五xx計數只算狀態五開頭的請求() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        registry.timer("http.server.requests", "uri", "/api/a", "status", "200").record(10, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests", "uri", "/api/a", "status", "404").record(10, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests", "uri", "/api/a", "status", "500").record(10, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests", "uri", "/api/a", "status", "503").record(10, TimeUnit.MILLISECONDS);

        SystemMetricsResponse m = service.currentMetrics();

        assertThat(m.http5xxCount()).isEqualTo(2L);
    }

    @Test
    void 資料庫連不上時回DOWN而指標照常回傳() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("連線失敗"));

        SystemMetricsResponse m = service.currentMetrics();

        assertThat(m.dbStatus()).isEqualTo("DOWN");
        assertThat(m.dbPingMillis()).isNull();
        // 完全沒註冊任何指標也不噴錯，全部回預設值
        assertThat(m.heapUsedBytes()).isZero();
        assertThat(m.httpRequestCount()).isZero();
    }
}
