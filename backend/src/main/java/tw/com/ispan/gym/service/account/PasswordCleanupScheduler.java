package tw.com.ispan.gym.service.account;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.repository.account.PasswordResetTokenRepository;
import tw.com.ispan.gym.security.InMemoryRateLimiter;

/**
 * 密碼管理的兩項 housekeeping，各自的排程原本就寫在 sealed spec 裡但一直沒有真的接上：
 * 清掉 {@link InMemoryRateLimiter} 裡沒有活動的舊 key（避免 map 隨時間無限增長），
 * 以及刪除已經沒有業務用途、保留期已過的 {@code password_reset_token} 舊列。
 */
@Component
public class PasswordCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(PasswordCleanupScheduler.class);

    /**
     * 必須大於限流所有呼叫端用到的最長視窗，才不會清掉還在計算中的資料——
     * 目前最長是忘記密碼 email／IP 的每小時上限，這裡抓 2 小時留餘裕。
     */
    private static final Duration RATE_LIMITER_MAX_IDLE = Duration.ofHours(2);

    /** 已失效 Reset Token 的保留期，過了才真的刪除；留一段時間方便事後排查 */
    private static final Duration RESET_TOKEN_RETENTION = Duration.ofDays(7);

    private final InMemoryRateLimiter rateLimiter;
    private final PasswordResetTokenRepository passwordResetTokens;

    public PasswordCleanupScheduler(InMemoryRateLimiter rateLimiter,
            PasswordResetTokenRepository passwordResetTokens) {
        this.rateLimiter = rateLimiter;
        this.passwordResetTokens = passwordResetTokens;
    }

    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.MINUTES)
    public void evictStaleRateLimiterEntries() {
        rateLimiter.evictStaleEntries(RATE_LIMITER_MAX_IDLE);
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Taipei")
    @Transactional
    public void deleteStaleResetTokens() {
        LocalDateTime cutoff = LocalDateTime.now().minus(RESET_TOKEN_RETENTION);
        int deleted = passwordResetTokens.deleteStaleTokens(cutoff);
        if (deleted > 0) {
            log.info("已清除 {} 筆過期的 password_reset_token 舊列", deleted);
        }
    }
}
