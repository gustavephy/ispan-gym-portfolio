package tw.com.ispan.gym.service.account.oauth;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OAuthExchangeCode;
import tw.com.ispan.gym.entity.account.OAuthRegistrationTicket;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthRedirectReason;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository;
import tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.security.AccountLoginPolicy;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;
import tw.com.ispan.gym.security.oauth.GoogleIdentity;

/**
 * Google LOGIN 的業務判定（見 memory/google_oauth_phase_scope.md 的登入判定順序）。
 * 判定順序固定：先以 (GOOGLE, sub) 找既有綁定，找到就只信任這個結果，不再用 Email
 * 查找其他 Account；找不到才走 Email 正規化與新使用者判斷。
 */
@Service
public class GoogleOAuthLoginService {

    public sealed interface Outcome permits Authenticated, RegistrationRequired, Rejected {}

    public record Authenticated(String rawExchangeCode) implements Outcome {}

    public record RegistrationRequired(String rawRegistrationToken) implements Outcome {}

    public record Rejected(OAuthRedirectReason reason) implements Outcome {}

    private final OauthAccountRepository oauthAccounts;
    private final AccountRepository accounts;
    private final AccountLoginPolicy loginPolicy;
    private final OAuthRegistrationTicketRepository registrationTickets;
    private final OAuthExchangeCodeRepository exchangeCodes;
    private final OpaqueTokenGenerator tokenGenerator;
    private final GoogleOAuthProperties properties;
    private final Clock clock;

    public GoogleOAuthLoginService(OauthAccountRepository oauthAccounts, AccountRepository accounts,
            AccountLoginPolicy loginPolicy, OAuthRegistrationTicketRepository registrationTickets,
            OAuthExchangeCodeRepository exchangeCodes, OpaqueTokenGenerator tokenGenerator,
            GoogleOAuthProperties properties, Clock clock) {
        this.oauthAccounts = oauthAccounts;
        this.accounts = accounts;
        this.loginPolicy = loginPolicy;
        this.registrationTickets = registrationTickets;
        this.exchangeCodes = exchangeCodes;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Outcome login(GoogleIdentity identity, String returnPath) {
        Optional<Account> existing = oauthAccounts.findAccountByProviderAndSubject(
                OAuthProvider.GOOGLE, identity.subject());
        if (existing.isPresent()) {
            return handleExistingBinding(existing.get(), identity, returnPath);
        }
        return handleNewIdentity(identity);
    }

    private Outcome handleExistingBinding(Account account, GoogleIdentity identity, String returnPath) {
        if (!loginPolicy.isLoginAllowed(account)) {
            return new Rejected(OAuthRedirectReason.ACCOUNT_DISABLED);
        }

        // 已綁定帳號的登入，即使這次 Google 沒回傳/回傳未驗證的 email 也不重新判定身分，
        // 只更新顯示用資訊；主要憑證永遠是 (GOOGLE, sub)
        oauthAccounts.findByOauthProviderAndProviderUserId(OAuthProvider.GOOGLE, identity.subject())
                .ifPresent(binding -> {
                    binding.setProviderEmail(identity.email());
                    binding.setProviderEmailVerified(identity.emailVerified());
                    binding.setLastLoginAt(LocalDateTime.now(clock));
                });

        return new Authenticated(issueExchangeCode(account, returnPath));
    }

    private Outcome handleNewIdentity(GoogleIdentity identity) {
        if (identity.email() == null || !identity.emailVerified()) {
            return new Rejected(OAuthRedirectReason.EMAIL_NOT_VERIFIED);
        }
        String normalizedEmail = normalize(identity.email());
        if (accounts.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            // 不自動綁定，也不透露既有帳號的任何狀態——見 sealed spec 的核心決策
            return new Rejected(OAuthRedirectReason.ACCOUNT_LINK_REQUIRED);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 同一個 (provider, sub) 只保留最新一張有效 ticket，避免多分頁各自持有一張
        registrationTickets.supersedePendingTickets(OAuthProvider.GOOGLE, identity.subject(), now);

        String rawToken = tokenGenerator.generate();
        OAuthRegistrationTicket ticket = new OAuthRegistrationTicket();
        ticket.setTokenHash(tokenGenerator.hash(rawToken));
        ticket.setOauthProvider(OAuthProvider.GOOGLE);
        ticket.setProviderUserId(identity.subject());
        ticket.setProviderEmail(normalizedEmail);
        ticket.setProviderEmailVerified(true);
        ticket.setSuggestedFirstName(identity.givenName());
        ticket.setSuggestedLastName(identity.familyName());
        ticket.setPictureUrl(identity.picture());
        ticket.setExpiresAt(now.plus(properties.registrationTicketTtl()));
        registrationTickets.save(ticket);

        return new RegistrationRequired(rawToken);
    }

    private String issueExchangeCode(Account account, String returnPath) {
        String rawCode = tokenGenerator.generate();
        OAuthExchangeCode code = new OAuthExchangeCode();
        code.setCodeHash(tokenGenerator.hash(rawCode));
        code.setAccount(account);
        code.setReturnPath(returnPath);
        code.setExpiresAt(LocalDateTime.now(clock).plus(properties.exchangeCodeTtl()));
        exchangeCodes.save(code);
        return rawCode;
    }

    private String normalize(String rawEmail) {
        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }
}
