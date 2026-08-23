package tw.com.ispan.gym.service.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository;
import tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository;
import tw.com.ispan.gym.repository.account.OAuthTransactionRepository;
import tw.com.ispan.gym.repository.account.PasswordResetTokenRepository;
import tw.com.ispan.gym.security.InMemoryRateLimiter;

/**
 * 純 Mockito 單元測試：對應必補案例「三種 OAuth 短效資料的 retention 清理」
 * 「尚未到 retention 的資料不刪」「Scheduler 呼叫三個 Repository」。
 *
 * <p>用固定 {@link Clock} 確認每個方法真的把「現在－7 天」當 cutoff 傳給對應的
 * repository——「尚未到 retention 的資料不刪」這件事的實際保證在 repository 那條
 * SQL 的 WHERE 子句（已經是既有、真實 DB E2E 驗證過的邏輯，這次沒有改），這裡驗證
 * 的是 Scheduler 算出的 cutoff 本身沒有算錯，錯一個方向就會變成「太早刪」或
 * 「永遠不刪」。
 */
class AuthenticationCleanupSchedulerTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 22, 3, 30, 0);
    private static final Duration RETENTION = Duration.ofDays(7);

    private final InMemoryRateLimiter rateLimiter = mock(InMemoryRateLimiter.class);
    private final PasswordResetTokenRepository passwordResetTokens = mock(PasswordResetTokenRepository.class);
    private final OAuthTransactionRepository oauthTransactions = mock(OAuthTransactionRepository.class);
    private final OAuthRegistrationTicketRepository oauthRegistrationTickets =
            mock(OAuthRegistrationTicketRepository.class);
    private final OAuthExchangeCodeRepository oauthExchangeCodes = mock(OAuthExchangeCodeRepository.class);
    private final Clock clock = Clock.fixed(
            FIXED_NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    private final AuthenticationCleanupScheduler scheduler = new AuthenticationCleanupScheduler(
            rateLimiter, passwordResetTokens, oauthTransactions, oauthRegistrationTickets, oauthExchangeCodes, clock);

    @Test
    void evictStaleRateLimiterEntries_呼叫RateLimiter的Evict() {
        scheduler.evictStaleRateLimiterEntries();

        verify(rateLimiter).evictStaleEntries(Duration.ofHours(2));
    }

    @Test
    void deleteStaleResetTokens_套用7天Retention() {
        scheduler.deleteStaleResetTokens();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(passwordResetTokens).deleteStaleTokens(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(FIXED_NOW.minus(RETENTION));
    }

    /** 對應必補案例「三種 OAuth 短效資料的 retention 清理」之一：OAuthTransaction */
    @Test
    void deleteStaleOAuthTransactions_套用7天Retention() {
        scheduler.deleteStaleOAuthTransactions();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(oauthTransactions).deleteStaleTransactions(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(FIXED_NOW.minus(RETENTION));
    }

    /** 對應必補案例「三種 OAuth 短效資料的 retention 清理」之二：OAuthRegistrationTicket */
    @Test
    void deleteStaleOAuthRegistrationTickets_套用7天Retention() {
        scheduler.deleteStaleOAuthRegistrationTickets();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(oauthRegistrationTickets).deleteStaleTickets(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(FIXED_NOW.minus(RETENTION));
    }

    /** 對應必補案例「三種 OAuth 短效資料的 retention 清理」之三：OAuthExchangeCode */
    @Test
    void deleteStaleOAuthExchangeCodes_套用7天Retention() {
        scheduler.deleteStaleOAuthExchangeCodes();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(oauthExchangeCodes).deleteStaleCodes(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(FIXED_NOW.minus(RETENTION));
    }

    /** 對應必補案例「Scheduler 呼叫三個 Repository」——一次跑完全部方法，四個 repository 各自都有被呼叫到 */
    @Test
    void 全部清理方法各自呼叫對應Repository_互不干擾() {
        scheduler.deleteStaleResetTokens();
        scheduler.deleteStaleOAuthTransactions();
        scheduler.deleteStaleOAuthRegistrationTickets();
        scheduler.deleteStaleOAuthExchangeCodes();

        verify(passwordResetTokens).deleteStaleTokens(any());
        verify(oauthTransactions).deleteStaleTransactions(any());
        verify(oauthRegistrationTickets).deleteStaleTickets(any());
        verify(oauthExchangeCodes).deleteStaleCodes(any());
    }

    @Test
    void 沒有過期資料時不記Log_但仍然呼叫Repository() {
        when(oauthTransactions.deleteStaleTransactions(any())).thenReturn(0);

        scheduler.deleteStaleOAuthTransactions();

        verify(oauthTransactions).deleteStaleTransactions(eq(FIXED_NOW.minus(RETENTION)));
    }
}
