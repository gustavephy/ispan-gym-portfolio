package tw.com.ispan.gym.service.account.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OAuthTransaction;
import tw.com.ispan.gym.entity.account.OauthAccount;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthTransactionPurpose;
import tw.com.ispan.gym.exception.OAuthAlreadyLinkedException;
import tw.com.ispan.gym.exception.OAuthLinkRateLimitedException;
import tw.com.ispan.gym.exception.ReauthenticationRequiredException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OAuthTransactionRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.security.InMemoryRateLimiter;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 純 Mockito 單元測試：對應必補案例「密碼錯誤拒絕」「已綁定不能重複發起」「舊 LINK
 * transaction 變成 SUPERSEDED」。悲觀鎖本身的併發序列化行為要靠真實 DB 驗證（見
 * scripts/test-google-oauth-core-e2e.ps1），這裡只驗證程式碼的判斷順序與呼叫序。
 */
class GoogleOAuthLinkInitiationServiceTest {

    private static final Integer ACCOUNT_ID = 30;
    private static final String CURRENT_PASSWORD = "Current@2026";

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final OauthAccountRepository oauthAccounts = mock(OauthAccountRepository.class);
    private final OAuthTransactionRepository transactions = mock(OAuthTransactionRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final InMemoryRateLimiter rateLimiter = mock(InMemoryRateLimiter.class);
    private final OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
    private final GoogleOAuthProperties properties = mock(GoogleOAuthProperties.class);
    private final Clock clock = Clock.systemDefaultZone();

    private final GoogleOAuthLinkInitiationService service = new GoogleOAuthLinkInitiationService(
            accounts, oauthAccounts, transactions, passwordEncoder, rateLimiter, tokenGenerator, properties, clock);

    /** 其他案例都測「功能已啟用」下的行為，停用本身的行為見下面獨立案例 */
    @BeforeEach
    void enableByDefault() {
        when(properties.isEnabled()).thenReturn(true);
    }

    /** 對應「阻止停用狀態發起 Google 帳號綁定」：最前面直接中止，完全不碰任何後續邏輯 */
    @Test
    void initiate_功能停用時_最前面直接丟ResourceNotFound_不做任何事() {
        when(properties.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.initiate(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(accounts, never()).findById(any());
        verify(accounts, never()).findByIdForUpdate(any());
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(transactions, never()).supersedePendingLinkTransactions(any(), any());
        verify(transactions, never()).save(any());
    }

    @Test
    void initiate_Account不存在_丟ResourceNotFound() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiate(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(rateLimiter, never()).tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    /** 對應必補案例「OAuth-only Account 無法用密碼重新驗證」 */
    @Test
    void initiate_OAuthOnlyAccount沒有密碼_丟ReauthenticationRequired() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf(null)));

        assertThatThrownBy(() -> service.initiate(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ReauthenticationRequiredException.class);
        verify(rateLimiter, never()).tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void initiate_限流超過上限_丟ReauthenticationRequired_不比對密碼() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(eq("oauth-link-reauth:" + ACCOUNT_ID), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.initiate(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ReauthenticationRequiredException.class);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    /** 對應必補案例「密碼錯誤拒絕」 */
    @Test
    void initiate_密碼錯誤_丟ReauthenticationRequired_不清除限流計數() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.initiate(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ReauthenticationRequiredException.class);
        verify(rateLimiter, never()).clear(anyString());
        verify(accounts, never()).findByIdForUpdate(any());
    }

    /** 鎖後複查：驗證通過到取得鎖之間密碼被別的請求改掉 */
    @Test
    void initiate_鎖前通過但鎖後密碼不符_丟ReauthenticationRequired() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(true);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("changed-hash")));
        when(passwordEncoder.matches(CURRENT_PASSWORD, "changed-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.initiate(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ReauthenticationRequiredException.class);
        verify(transactions, never()).save(any());
    }

    /** 對應必補案例「已綁定不能重複發起」 */
    @Test
    void initiate_已經綁定Google_丟OAuthAlreadyLinked() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(true);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(new OauthAccount()));

        assertThatThrownBy(() -> service.initiate(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(OAuthAlreadyLinkedException.class);
        verify(transactions, never()).supersedePendingLinkTransactions(any(), any());
        verify(transactions, never()).save(any());
    }

    /** 對應必補案例「LINK 正確密碼仍受 initiate 限流」——已經證明知道密碼不代表可以無限發起 */
    @Test
    void initiate_密碼正確但Initiate額度已用完_丟OAuthLinkRateLimited_不Supersede不建立() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(eq("oauth-link-reauth:" + ACCOUNT_ID), anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(true);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(eq("oauth-link-initiate:" + ACCOUNT_ID), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.initiate(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(OAuthLinkRateLimitedException.class);
        verify(transactions, never()).supersedePendingLinkTransactions(any(), any());
        verify(transactions, never()).save(any());
    }

    @Test
    void initiate_Initiate十分鐘額度足夠但小時額度已用完_仍丟OAuthLinkRateLimited() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(eq("oauth-link-reauth:" + ACCOUNT_ID), anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(true);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(eq("oauth-link-initiate:" + ACCOUNT_ID), anyInt(), any())).thenReturn(true);
        when(rateLimiter.tryAcquire(eq("oauth-link-initiate-hourly:" + ACCOUNT_ID), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.initiate(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(OAuthLinkRateLimitedException.class);
        verify(transactions, never()).save(any());
    }

    /** 對應必補案例「舊 LINK transaction 變成 SUPERSEDED」與成功路徑本身 */
    @Test
    void initiate_成功時先Supersede舊Transaction再建立新的_回傳RawState() {
        Account locked = accountOf("hash");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(true);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(locked));
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn("raw-link-state");
        when(tokenGenerator.hash("raw-link-state")).thenReturn("hash-of-link-state");
        when(properties.transactionTtl()).thenReturn(Duration.ofMinutes(10));

        String rawState = service.initiate(ACCOUNT_ID, CURRENT_PASSWORD);

        assertThat(rawState).isEqualTo("raw-link-state");
        // 對應必補案例「reauth 成功只清除 reauth 額度」與「LINK initiate 成功不清除資源額度」：
        // 整個成功路徑只清過 reauth 這一個 key，initiate 的兩個 key 都不曾被 clear
        verify(rateLimiter).clear("oauth-link-reauth:" + ACCOUNT_ID);
        verify(rateLimiter, org.mockito.Mockito.times(1)).clear(anyString());
        verify(rateLimiter, never()).clear("oauth-link-initiate:" + ACCOUNT_ID);
        verify(rateLimiter, never()).clear("oauth-link-initiate-hourly:" + ACCOUNT_ID);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(transactions);
        inOrder.verify(transactions).supersedePendingLinkTransactions(eq(ACCOUNT_ID), any());
        inOrder.verify(transactions).save(any());

        ArgumentCaptor<OAuthTransaction> captor = ArgumentCaptor.forClass(OAuthTransaction.class);
        verify(transactions).save(captor.capture());
        OAuthTransaction saved = captor.getValue();
        assertThat(saved.getStateHash()).isEqualTo("hash-of-link-state");
        assertThat(saved.getOauthProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(saved.getPurpose()).isEqualTo(OAuthTransactionPurpose.LINK);
        assertThat(saved.getAccount()).isSameAs(locked);
        assertThat(saved.getExpiresAt()).isNotNull();
    }

    private Account accountOf(String passwordHash) {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("user@example.com");
        account.setPasswordHash(passwordHash);
        return account;
    }
}
