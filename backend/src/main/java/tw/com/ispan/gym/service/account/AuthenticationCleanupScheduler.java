package tw.com.ispan.gym.service.account;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository;
import tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository;
import tw.com.ispan.gym.repository.account.OAuthTransactionRepository;
import tw.com.ispan.gym.repository.account.PasswordResetTokenRepository;
import tw.com.ispan.gym.security.InMemoryRateLimiter;

/**
 * 帳號安全域短效憑證的 housekeeping——原名 {@code PasswordCleanupScheduler}，只管
 * {@link PasswordResetTokenRepository}；這次改名反映它現在同時涵蓋密碼與 Google OAuth
 * 兩個域的短效資料，職責擴大了才改名，不是為了改名而改名，也沒有順手擴充其他清理範圍。
 *
 * <p>{@link InMemoryRateLimiter} 的過期 key 清理，以及每個 repository 各自的
 * {@code deleteStale*} 都遵循同一套規則：清掉已經沒有業務用途、且死亡後已超過保留期
 * 的舊列——「保留期」一律從「變成沒有用的那一刻」（用掉／作廢／自然過期）起算，不是
 * 從建立時間起算，理由見各 repository 自己的 Javadoc。
 */
@Component
public class AuthenticationCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationCleanupScheduler.class);

    /**
     * 必須大於限流所有呼叫端用到的最長視窗，才不會清掉還在計算中的資料——
     * 目前最長是忘記密碼 email／IP 的每小時上限，這裡抓 2 小時留餘裕。
     */
    private static final Duration RATE_LIMITER_MAX_IDLE = Duration.ofHours(2);

    /** 已失效短效憑證的保留期，過了才真的刪除；留一段時間方便事後排查，四種短效憑證共用同一個值 */
    private static final Duration RETENTION = Duration.ofDays(7);

    private final InMemoryRateLimiter rateLimiter;
    private final PasswordResetTokenRepository passwordResetTokens;
    private final OAuthTransactionRepository oauthTransactions;
    private final OAuthRegistrationTicketRepository oauthRegistrationTickets;
    private final OAuthExchangeCodeRepository oauthExchangeCodes;
    private final Clock clock;

    public AuthenticationCleanupScheduler(InMemoryRateLimiter rateLimiter,
            PasswordResetTokenRepository passwordResetTokens, OAuthTransactionRepository oauthTransactions,
            OAuthRegistrationTicketRepository oauthRegistrationTickets,
            OAuthExchangeCodeRepository oauthExchangeCodes, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.passwordResetTokens = passwordResetTokens;
        this.oauthTransactions = oauthTransactions;
        this.oauthRegistrationTickets = oauthRegistrationTickets;
        this.oauthExchangeCodes = oauthExchangeCodes;
        this.clock = clock;
    }

    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.MINUTES)
    public void evictStaleRateLimiterEntries() {
        rateLimiter.evictStaleEntries(RATE_LIMITER_MAX_IDLE);
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Taipei")
    @Transactional
    public void deleteStaleResetTokens() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(RETENTION);
        int deleted = passwordResetTokens.deleteStaleTokens(cutoff);
        if (deleted > 0) {
            log.info("已清除 {} 筆過期的 password_reset_token 舊列", deleted);
        }
    }

    @Scheduled(cron = "0 35 3 * * *", zone = "Asia/Taipei")
    @Transactional
    public void deleteStaleOAuthTransactions() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(RETENTION);
        int deleted = oauthTransactions.deleteStaleTransactions(cutoff);
        if (deleted > 0) {
            log.info("已清除 {} 筆過期的 oauth_transaction 舊列", deleted);
        }
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Taipei")
    @Transactional
    public void deleteStaleOAuthRegistrationTickets() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(RETENTION);
        int deleted = oauthRegistrationTickets.deleteStaleTickets(cutoff);
        if (deleted > 0) {
            log.info("已清除 {} 筆過期的 oauth_registration_ticket 舊列", deleted);
        }
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "Asia/Taipei")
    @Transactional
    public void deleteStaleOAuthExchangeCodes() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(RETENTION);
        int deleted = oauthExchangeCodes.deleteStaleCodes(cutoff);
        if (deleted > 0) {
            log.info("已清除 {} 筆過期的 oauth_exchange_code 舊列", deleted);
        }
    }
}
