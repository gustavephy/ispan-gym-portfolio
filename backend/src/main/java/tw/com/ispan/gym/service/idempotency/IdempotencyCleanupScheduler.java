package tw.com.ispan.gym.service.idempotency;

import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.repository.idempotency.IdempotencyRequestRepository;

/**
 * 冪等保護記錄（idempotency_request）的排程清理。每筆記錄建立時就帶著 expiresAt
 * （見 IdempotencyService），過期後失去防重複送出的保護作用，這裡定期清掉，
 * 避免結帳/取消動作越多、這張表就無止盡長大。
 *
 * <p>不像帳號安全域那批 Scheduler 注入 {@code Clock}——那個 bean 目前刻意只給
 * OAuth 那批新程式用（見 ClockConfig 類別註解），沒有回頭把既有慣例一起改，
 * 這裡跟著 IdempotencyService 本身的寫法，直接用 {@link LocalDateTime#now()}。
 */
@Component
public class IdempotencyCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

    /** 過期後留一天緩衝期才真的刪除，方便萬一要排查重複送出問題時還找得到記錄 */
    private static final Duration RETENTION = Duration.ofDays(1);

    private final IdempotencyRequestRepository requests;

    public IdempotencyCleanupScheduler(IdempotencyRequestRepository requests) {
        this.requests = requests;
    }

    @Scheduled(cron = "0 50 3 * * *", zone = "Asia/Taipei")
    @Transactional
    public void deleteStaleIdempotencyRequests() {
        LocalDateTime cutoff = LocalDateTime.now().minus(RETENTION);
        int deleted = requests.deleteStaleRequests(cutoff);
        if (deleted > 0) {
            log.info("已清除 {} 筆過期的 idempotency_request 舊列", deleted);
        }
    }
}
