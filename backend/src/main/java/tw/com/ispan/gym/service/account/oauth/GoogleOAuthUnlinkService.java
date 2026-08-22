package tw.com.ispan.gym.service.account.oauth;

import java.time.Duration;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 解除 Google 綁定：密碼重新驗證 → Account-first 悲觀鎖 → 刪除 {@code oauth_account}
 * → {@code credentialsVersion + 1}。第一版採已定案的保守策略：解除成功後既有
 * HTTP JWT 與 STOMP session 一律失效，使用者以密碼重新登入
 * （見 docs/google-oauth-design-notes.md 的「Reauthentication requirement」）。
 *
 * <p>OAuth-only Account（{@code passwordHash == null}）不能解除唯一登入方式——這是
 * 第一個檢查、在任何密碼比對之前就先擋下，因為這種帳號根本沒有密碼可以拿來重新驗證。
 */
@Service
public class GoogleOAuthUnlinkService {

    private static final int REAUTH_MAX_ATTEMPTS = 5;
    private static final Duration REAUTH_WINDOW = Duration.ofMinutes(15);

    private final AccountRepository accounts;
    private final OauthAccountRepository oauthAccounts;
    private final PasswordEncoder passwordEncoder;
    private final InMemoryRateLimiter rateLimiter;

    public GoogleOAuthUnlinkService(AccountRepository accounts, OauthAccountRepository oauthAccounts,
            PasswordEncoder passwordEncoder, InMemoryRateLimiter rateLimiter) {
        this.accounts = accounts;
        this.oauthAccounts = oauthAccounts;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public void unlink(Integer accountId, String currentPassword) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("帳號不存在"));
        if (account.getPasswordHash() == null) {
            throw new LastLoginMethodException("此帳號沒有本機密碼，Google 是唯一登入方式，無法解除");
        }

        String rateLimitKey = "oauth-unlink-reauth:" + accountId;
        if (!rateLimiter.tryAcquire(rateLimitKey, REAUTH_MAX_ATTEMPTS, REAUTH_WINDOW)) {
            throw new ReauthenticationRequiredException("密碼錯誤次數過多，請稍後再試");
        }
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new ReauthenticationRequiredException("目前密碼錯誤");
        }
        rateLimiter.clear(rateLimitKey);

        Account locked = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("帳號不存在"));
        if (!passwordEncoder.matches(currentPassword, locked.getPasswordHash())) {
            throw new ReauthenticationRequiredException("目前密碼錯誤");
        }

        OauthAccount binding = oauthAccounts.findByAccountIdAndOauthProvider(accountId, OAuthProvider.GOOGLE)
                .orElseThrow(() -> new ResourceNotFoundException("尚未綁定 Google 帳號"));
        oauthAccounts.delete(binding);

        // 保守策略：解除後全部既有 JWT／STOMP session 一律失效，不細分「主動解除」與
        // 「疑似被盜才解除」——第一版先簡單、安全的那個選項
        locked.setCredentialsVersion(locked.getCredentialsVersion() + 1);
    }
}
