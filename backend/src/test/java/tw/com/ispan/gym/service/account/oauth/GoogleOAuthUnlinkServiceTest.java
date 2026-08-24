package tw.com.ispan.gym.service.account.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
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

    private final GoogleOAuthUnlinkService service =
            new GoogleOAuthUnlinkService(accounts, oauthAccounts, passwordEncoder, rateLimiter);

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

    @Test
    void unlink_鎖前通過但鎖後密碼不符_丟ReauthenticationRequired() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hash")).thenReturn(true);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("changed-hash")));
        when(passwordEncoder.matches(CURRENT_PASSWORD, "changed-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.unlink(ACCOUNT_ID, CURRENT_PASSWORD))
                .isInstanceOf(ReauthenticationRequiredException.class);
        verify(oauthAccounts, never()).delete(any());
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
