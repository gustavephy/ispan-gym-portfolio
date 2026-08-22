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
import tw.com.ispan.gym.entity.account.OauthAccount;
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
 * Google LOGIN 的業務判定（見 docs/google-oauth-design-notes.md 的登入判定順序）。
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
        Optional<Integer> existingAccountId = oauthAccounts.findAccountIdByProviderAndSubject(
                OAuthProvider.GOOGLE, identity.subject());
        if (existingAccountId.isPresent()) {
            return handleExistingBinding(existingAccountId.get(), identity, returnPath);
        }
        return handleNewIdentity(identity);
    }

    /**
     * 上面的 scalar 定位是非鎖定讀取，這裡鎖 Account——跟 LINK／UNLINK／{@code PasswordService}
     * 同一套 Account-first 鎖序，因為這裡會真的核發一張帶帳號憑證的 Exchange Code，不是
     * 純查詢。鎖進來之後必須重查一次 (provider, sub) 是否還指向同一個 Account：上面 scalar
     * 定位到鎖到手之間的窗口內，可能有併發的 UNLINK 已經把這筆綁定刪掉了——不重查就會對
     * 一個其實已經解除綁定的身分核發一張核發當下看似合法、實際上業務上不該存在的 Code
     * （2026-08-22 code review 抓到的落差；真正擋住「拿這張 Code 換出 JWT」的是
     * {@code GoogleOAuthExchangeService} 的 credentialsVersion 快照比對，這裡是同一個
     * 修正範圍的另一半，避免流程一開始就走岔）。
     */
    private Outcome handleExistingBinding(Integer accountId, GoogleIdentity identity, String returnPath) {
        Account account = accounts.findByIdForUpdate(accountId).orElse(null);
        if (account == null || !loginPolicy.isLoginAllowed(account)) {
            return new Rejected(OAuthRedirectReason.ACCOUNT_DISABLED);
        }

        Optional<OauthAccount> binding =
                oauthAccounts.findByOauthProviderAndProviderUserId(OAuthProvider.GOOGLE, identity.subject());
        if (binding.isEmpty() || !accountId.equals(binding.get().getAccount().getId())) {
            // 鎖進來之前綁定已經被解除——不是帳號被停用，是這次登入本身已經失去依據，
            // 請使用者重新走一次 Google 登入，跟其他「flow 已經失效」的情境用同一個理由
            return new Rejected(OAuthRedirectReason.FLOW_EXPIRED);
        }

        // 已綁定帳號的登入，即使這次 Google 沒回傳/回傳未驗證的 email 也不重新判定身分，
        // 只更新顯示用資訊；主要憑證永遠是 (GOOGLE, sub)
        binding.get().setProviderEmail(identity.email());
        binding.get().setProviderEmailVerified(identity.emailVerified());
        binding.get().setLastLoginAt(LocalDateTime.now(clock));

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
        // 核發當下的版本快照——見 OAuthExchangeCode 的欄位註解，兌換時會拿目前的
        // credentialsVersion 跟這裡比對
        code.setIssuedCredentialsVersion(account.getCredentialsVersion());
        code.setReturnPath(returnPath);
        code.setExpiresAt(LocalDateTime.now(clock).plus(properties.exchangeCodeTtl()));
        exchangeCodes.save(code);
        return rawCode;
    }

    private String normalize(String rawEmail) {
        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }
}
