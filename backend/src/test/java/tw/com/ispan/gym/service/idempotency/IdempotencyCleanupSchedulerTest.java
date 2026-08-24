package tw.com.ispan.gym.service.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tw.com.ispan.gym.repository.idempotency.IdempotencyRequestRepository;

/**
 * 純 Mockito 單元測試。這支排程沒有注入 Clock（見類別 Javadoc 說明原因），
 * 改用「cutoff 落在 now-RETENTION 附近的容忍區間」驗證方向沒算反，
 * 一樣能抓到「cutoff 算反了」這種錯，只是不到 Clock.fixed() 那樣精確到毫秒。
 */
class IdempotencyCleanupSchedulerTest {

    private static final Duration RETENTION = Duration.ofDays(1);

    private final IdempotencyRequestRepository requests = mock(IdempotencyRequestRepository.class);
    private final IdempotencyCleanupScheduler scheduler = new IdempotencyCleanupScheduler(requests);

    @Test
    void 套用一天retention且cutoff落在過去() {
        LocalDateTime before = LocalDateTime.now().minus(RETENTION);

        scheduler.deleteStaleIdempotencyRequests();

        LocalDateTime after = LocalDateTime.now().minus(RETENTION);
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(requests).deleteStaleRequests(captor.capture());
        assertThat(captor.getValue()).isBetween(before.minusSeconds(2), after.plusSeconds(2));
    }

    @Test
    void 沒有過期資料時不記Log但仍呼叫Repository() {
        when(requests.deleteStaleRequests(any())).thenReturn(0);

        scheduler.deleteStaleIdempotencyRequests();

        verify(requests).deleteStaleRequests(any());
    }
}
