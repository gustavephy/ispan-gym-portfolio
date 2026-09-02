package tw.com.ispan.gym.service.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Tags;
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
