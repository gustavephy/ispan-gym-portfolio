package tw.com.ispan.gym.service.account.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OauthAccount;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.exception.LastLoginMethodException;
import tw.com.ispan.gym.exception.ReauthenticationRequiredException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.security.InMemoryRateLimiter;

/**
 * 純 Mockito 單元測試：對應必補案例「唯一登入方式不能解除」「有密碼時可以解除」
 * 「解除後 credentialsVersion + 1」。
 */
class GoogleOAuthUnlinkServiceTest {

    private static final Integer ACCOUNT_ID = 30;
    private static final String CURRENT_PASSWORD = "Current@2026";

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final OauthAccountRepository oauthAccounts = mock(OauthAccountRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final InMemoryRateLimiter rateLimiter = mock(InMemoryRateLimiter.class);
    private final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);

    private final GoogleOAuthUnlinkService service =
            new GoogleOAuthUnlinkService(accounts, oauthAccounts, passwordEncoder, rateLimiter, entityManager);

    @Test
    void unlink_Account不存在_丟ResourceNotFound() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlink(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** 對應必補案例「唯一登入方式不能解除」——OAuth-only Account 連密碼比對都不用做就先擋下 */
    @Test
    void unlink_OAuthOnlyAccount沒有密碼_丟LastLoginMethod_不觸碰限流() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf(null)));

        assertThatThrownBy(() -> service.unlink(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(LastLoginMethodException.class);
        verify(rateLimiter, never()).tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(oauthAccounts, never()).delete(any());
    }

    @Test
    void unlink_限流超過上限_丟ReauthenticationRequired() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(eq("oauth-unlink-reauth:" + ACCOUNT_ID), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.unlink(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ReauthenticationRequiredException.class);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    /** 對應必補案例「密碼錯誤不能解除」 */
    @Test
    void unlink_密碼錯誤_丟ReauthenticationRequired() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.unlink(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ReauthenticationRequiredException.class);
        verify(accounts, never()).findByIdForUpdate(any());
    }

    /**
     * OSIV stale entity 回歸（2026-09-01 真實 DB E2E 抓到）：findByIdForUpdate 拿到鎖前舊實例
     * （passwordHash 仍是舊值），是 {@code entityManager.refresh()} 讓它看到並行 change-password
     * 剛把密碼改成的新值——拿鎖後的重新驗證因此用新 hash 比對、擋下已失效的舊密碼。
     */
    @Test
    void unlink_拿鎖後refresh讀到並行改過的密碼_舊密碼重驗被擋下() {
        Account staleInstance = accountOf("hash");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(staleInstance));
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(true);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(staleInstance));
        doAnswer(inv -> {
            ((Account) inv.getArgument(0)).setPasswordHash("changed-by-concurrent-request");
            return null;
        }).when(entityManager).refresh(any(Account.class));
        when(passwordEncoder.matches(CURRENT_PASSWORD, "changed-by-concurrent-request")).thenReturn(false);

        assertThatThrownBy(() -> service.unlink(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ReauthenticationRequiredException.class);
        verify(oauthAccounts, never()).delete(any());
    }

    /** refresh 必須在拿鎖之後、鎖後密碼重驗之前 */
    @Test
    void unlink_refresh順序_在findByIdForUpdate之後_在鎖後重驗之前() {
        Account locked = accountOf("hash");
        OauthAccount binding = new OauthAccount();
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(locked));
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(true);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(locked));
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(binding));

        service.unlink(ACCOUNT_ID, CURRENT_PASSWORD);

        InOrder inOrder = inOrder(accounts, entityManager, oauthAccounts);
        inOrder.verify(accounts).findByIdForUpdate(ACCOUNT_ID);
        inOrder.verify(entityManager).refresh(locked);
        inOrder.verify(oauthAccounts).delete(binding);
    }

    @Test
    void unlink_沒有Google綁定_丟ResourceNotFound() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(true);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlink(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** 對應必補案例「有密碼時可以解除」與「解除後 credentialsVersion + 1」 */
    @Test
    void unlink_成功時刪除綁定並遞增CredentialsVersion() {
        Account locked = accountOf("hash");
        locked.setCredentialsVersion(2);
        OauthAccount binding = new OauthAccount();
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(true);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(locked));
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(binding));

        service.unlink(ACCOUNT_ID, CURRENT_PASSWORD);

        verify(oauthAccounts).delete(binding);
        assertThat(locked.getCredentialsVersion()).isEqualTo(3);
        verify(rateLimiter).clear("oauth-unlink-reauth:" + ACCOUNT_ID);
    }

    private Account accountOf(String passwordHash) {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("user@example.com");
        account.setPasswordHash(passwordHash);
        account.setCredentialsVersion(0);
        return account;
    }
}
