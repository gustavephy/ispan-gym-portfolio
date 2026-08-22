package tw.com.ispan.gym.service.account.oauth;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OauthAccount;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthRedirectReason;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.security.AccountLoginPolicy;
import tw.com.ispan.gym.security.oauth.GoogleIdentity;

/**
 * Google LINK 的業務判定，在 Callback 階段執行——發起 LINK 時（見
 * {@link GoogleOAuthLinkInitiationService}）已經檢查過密碼、是否已綁定，但 Google
 * 授權期間可能有其他請求插入變化，這裡是取得 transaction 之後的最後一道防禦性複查，
 * 不可省略。
 */
@Service
public class GoogleOAuthLinkService {

    public sealed interface Outcome permits Linked, Rejected {}

    public record Linked() implements Outcome {}

    public record Rejected(OAuthRedirectReason reason) implements Outcome {}

    private final AccountRepository accounts;
    private final OauthAccountRepository oauthAccounts;
    private final AccountLoginPolicy loginPolicy;
    private final Clock clock;

    public GoogleOAuthLinkService(AccountRepository accounts, OauthAccountRepository oauthAccounts,
            AccountLoginPolicy loginPolicy, Clock clock) {
        this.accounts = accounts;
        this.oauthAccounts = oauthAccounts;
        this.loginPolicy = loginPolicy;
        this.clock = clock;
    }

    @Transactional
    public Outcome link(Integer accountId, GoogleIdentity identity) {
        Account account = accounts.findById(accountId).orElse(null);
        if (account == null || !loginPolicy.isLoginAllowed(account)) {
            return new Rejected(OAuthRedirectReason.ACCOUNT_DISABLED);
        }

        boolean subjectBoundElsewhere = oauthAccounts
                .findAccountByProviderAndSubject(OAuthProvider.GOOGLE, identity.subject())
                .filter(bound -> !bound.getId().equals(accountId))
                .isPresent();
        if (subjectBoundElsewhere) {
            return new Rejected(OAuthRedirectReason.IDENTITY_IN_USE);
        }

        if (oauthAccounts.findByAccountIdAndOauthProvider(accountId, OAuthProvider.GOOGLE).isPresent()) {
            return new Rejected(OAuthRedirectReason.ALREADY_LINKED);
        }

        // v1 保守規則：Google email 必須與 Account email 一致，理由見
        // memory/google_oauth_phase_scope.md「產品規則細節」——密碼重驗證只證明操作者
        // 是本人，不代表允許綁一個完全不同 email 的 Google 身分，避免日後客服/稽核混亂
        if (identity.email() == null || !identity.emailVerified()
                || !normalize(identity.email()).equals(normalize(account.getEmail()))) {
            return new Rejected(OAuthRedirectReason.LINK_EMAIL_MISMATCH);
        }

        OauthAccount binding = new OauthAccount();
        binding.setAccount(account);
        binding.setOauthProvider(OAuthProvider.GOOGLE);
        binding.setProviderUserId(identity.subject());
        binding.setProviderEmail(identity.email());
        binding.setProviderEmailVerified(true);
        binding.setLastLoginAt(LocalDateTime.now(clock));
        oauthAccounts.save(binding);

        return new Linked();
    }

    private String normalize(String rawEmail) {
        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }
}
