package tw.com.ispan.gym.service.account.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * 純 Mockito 單元測試：對應 docs/google-oauth-design-notes.md 的登入判定順序——
 * 已綁定一律信任 (GOOGLE, sub)、不再用 Email 找其他 Account；未綁定才走 Email
 * 正規化與新使用者判斷；Email 未驗證或已存在都不建立/更新任何資料。
 */
class GoogleOAuthLoginServiceTest {

    private static final Integer ACCOUNT_ID = 30;

    private final OauthAccountRepository oauthAccounts = mock(OauthAccountRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountLoginPolicy loginPolicy = mock(AccountLoginPolicy.class);
    private final OAuthRegistrationTicketRepository registrationTickets = mock(OAuthRegistrationTicketRepository.class);
    private final OAuthExchangeCodeRepository exchangeCodes = mock(OAuthExchangeCodeRepository.class);
    private final OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
    private final GoogleOAuthProperties properties = mock(GoogleOAuthProperties.class);
    private final Clock clock = Clock.systemDefaultZone();

    private final GoogleOAuthLoginService service = new GoogleOAuthLoginService(
            oauthAccounts, accounts, loginPolicy, registrationTickets, exchangeCodes, tokenGenerator, properties,
            clock);

    // === 已綁定 (GOOGLE, sub) ====================================================

    @Test
    void login_已綁定且Policy允許_回傳Authenticated並發交換碼() {
        Account account = accountOf();
        OauthAccount binding = boundTo(account);
        when(oauthAccounts.findAccountIdByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(oauthAccounts.findByOauthProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(binding));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(tokenGenerator.generate()).thenReturn("raw-exchange-code");
        when(tokenGenerator.hash("raw-exchange-code")).thenReturn("hash-of-exchange-code");
        when(properties.exchangeCodeTtl()).thenReturn(Duration.ofSeconds(90));

        GoogleOAuthLoginService.Outcome outcome = service.login(identity(), "/orders");

        assertThat(outcome).isInstanceOf(GoogleOAuthLoginService.Authenticated.class);
        assertThat(((GoogleOAuthLoginService.Authenticated) outcome).rawExchangeCode()).isEqualTo("raw-exchange-code");

        ArgumentCaptor<OAuthExchangeCode> captor = ArgumentCaptor.forClass(OAuthExchangeCode.class);
        verify(exchangeCodes).save(captor.capture());
        OAuthExchangeCode saved = captor.getValue();
        assertThat(saved.getCodeHash()).isEqualTo("hash-of-exchange-code");
        assertThat(saved.getAccount()).isSameAs(account);
        assertThat(saved.getReturnPath()).isEqualTo("/orders");
        // 核發當下的版本快照——見 GoogleOAuthExchangeServiceTest 的版本比對案例
        assertThat(saved.getIssuedCredentialsVersion()).isEqualTo(account.getCredentialsVersion());

        // 不儲存 JWT／角色快照——OAuthExchangeCode 只有這四個欄位，交換時要重新判定權限
        assertThat(binding.getProviderEmail()).isEqualTo("user@example.com");
        assertThat(binding.getProviderEmailVerified()).isTrue();
        assertThat(binding.getLastLoginAt()).isNotNull();

        // 已綁定時不得再用 Email 搜尋其他 Account
        verify(accounts, never()).findByEmailIgnoreCase(anyString());
    }

    /** Google Email 改變仍登入原 Account——只更新顯示用欄位，不重新判定身分 */
    @Test
    void login_已綁定Google的Email改變時_仍登入原Account只更新顯示欄位() {
        Account account = accountOf();
        OauthAccount binding = boundTo(account);
        binding.setProviderEmail("old-email@example.com");
        when(oauthAccounts.findAccountIdByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(oauthAccounts.findByOauthProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(binding));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(tokenGenerator.generate()).thenReturn("raw-exchange-code");
        when(properties.exchangeCodeTtl()).thenReturn(Duration.ofSeconds(90));

        GoogleIdentity changedEmailIdentity = new GoogleIdentity(
                "google-sub-123", "new-email@example.com", true, "小明", "王", null);
        GoogleOAuthLoginService.Outcome outcome = service.login(changedEmailIdentity, null);

        assertThat(outcome).isInstanceOf(GoogleOAuthLoginService.Authenticated.class);
        assertThat(binding.getProviderEmail()).isEqualTo("new-email@example.com");
        // 仍是同一個 account——sub 才是身分主鍵，不因 email 變了就換一個帳號
        verify(accounts, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void login_已綁定但Policy拒絕_回傳RejectedAccountDisabled_不發交換碼() {
        Account account = accountOf();
        when(oauthAccounts.findAccountIdByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(false);

        GoogleOAuthLoginService.Outcome outcome = service.login(identity(), null);

        assertThat(outcome).isInstanceOf(GoogleOAuthLoginService.Rejected.class);
        assertThat(((GoogleOAuthLoginService.Rejected) outcome).reason()).isEqualTo(OAuthRedirectReason.ACCOUNT_DISABLED);
        verify(exchangeCodes, never()).save(any());
    }

    /** 對應 2026-08-22 code review：Account-first 鎖進來之前，UNLINK 已經把綁定刪掉了 */
    @Test
    void login_鎖進來後綁定已被解除_回傳RejectedFlowExpired_不發交換碼() {
        Account account = accountOf();
        when(oauthAccounts.findAccountIdByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(oauthAccounts.findByOauthProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());

        GoogleOAuthLoginService.Outcome outcome = service.login(identity(), null);

        assertThat(outcome).isInstanceOf(GoogleOAuthLoginService.Rejected.class);
        assertThat(((GoogleOAuthLoginService.Rejected) outcome).reason()).isEqualTo(OAuthRedirectReason.FLOW_EXPIRED);
        verify(exchangeCodes, never()).save(any());
    }

    /** 防禦性複查：鎖進來後綁定竟然指向別的 Account（理論上不可能，唯一約束會先擋下），仍不得核發 */
    @Test
    void login_鎖進來後綁定指向別的Account_回傳RejectedFlowExpired_不發交換碼() {
        Account account = accountOf();
        Account otherAccount = new Account();
        otherAccount.setId(999);
        when(oauthAccounts.findAccountIdByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(oauthAccounts.findByOauthProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(boundTo(otherAccount)));

        GoogleOAuthLoginService.Outcome outcome = service.login(identity(), null);

        assertThat(((GoogleOAuthLoginService.Rejected) outcome).reason()).isEqualTo(OAuthRedirectReason.FLOW_EXPIRED);
        verify(exchangeCodes, never()).save(any());
    }

    // === 未綁定 ===================================================================

    @Test
    void login_未綁定且EmailNull_拒絕EmailNotVerified() {
        when(oauthAccounts.findAccountIdByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        GoogleIdentity noEmail = new GoogleIdentity("google-sub-123", null, false, null, null, null);

        GoogleOAuthLoginService.Outcome outcome = service.login(noEmail, null);

        assertThat(outcome).isInstanceOf(GoogleOAuthLoginService.Rejected.class);
        assertThat(((GoogleOAuthLoginService.Rejected) outcome).reason()).isEqualTo(OAuthRedirectReason.EMAIL_NOT_VERIFIED);
        verify(registrationTickets, never()).save(any());
    }

    @Test
    void login_未綁定且Email未驗證_拒絕EmailNotVerified() {
        when(oauthAccounts.findAccountIdByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        GoogleIdentity unverified = new GoogleIdentity(
                "google-sub-123", "user@example.com", false, "小明", "王", null);

        GoogleOAuthLoginService.Outcome outcome = service.login(unverified, null);

        assertThat(outcome).isInstanceOf(GoogleOAuthLoginService.Rejected.class);
        assertThat(((GoogleOAuthLoginService.Rejected) outcome).reason()).isEqualTo(OAuthRedirectReason.EMAIL_NOT_VERIFIED);
        verify(registrationTickets, never()).save(any());
    }

    @Test
    void login_未綁定但Email已存在_拒絕AccountLinkRequired_不自動綁定() {
        when(oauthAccounts.findAccountIdByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(accountOf()));

        GoogleOAuthLoginService.Outcome outcome = service.login(identity(), null);

        assertThat(outcome).isInstanceOf(GoogleOAuthLoginService.Rejected.class);
        assertThat(((GoogleOAuthLoginService.Rejected) outcome).reason())
                .isEqualTo(OAuthRedirectReason.ACCOUNT_LINK_REQUIRED);
        verify(registrationTickets, never()).save(any());
        // 不自動綁定：不建立 OauthAccount，也不建 Account
        verify(oauthAccounts, never()).save(any());
    }

    @Test
    void login_全新且已驗證Email_建立RegistrationTicket並先Supersede舊Ticket() {
        when(oauthAccounts.findAccountIdByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn("raw-registration-token");
        when(tokenGenerator.hash("raw-registration-token")).thenReturn("hash-of-registration-token");
        when(properties.registrationTicketTtl()).thenReturn(Duration.ofMinutes(15));

        GoogleOAuthLoginService.Outcome outcome = service.login(identity(), null);

        assertThat(outcome).isInstanceOf(GoogleOAuthLoginService.RegistrationRequired.class);
        assertThat(((GoogleOAuthLoginService.RegistrationRequired) outcome).rawRegistrationToken())
                .isEqualTo("raw-registration-token");

        verify(registrationTickets).supersedePendingTickets(
                org.mockito.ArgumentMatchers.eq(OAuthProvider.GOOGLE),
                org.mockito.ArgumentMatchers.eq("google-sub-123"),
                any());

        ArgumentCaptor<OAuthRegistrationTicket> captor = ArgumentCaptor.forClass(OAuthRegistrationTicket.class);
        verify(registrationTickets).save(captor.capture());
        OAuthRegistrationTicket saved = captor.getValue();
        assertThat(saved.getTokenHash()).isEqualTo("hash-of-registration-token");
        assertThat(saved.getOauthProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(saved.getProviderUserId()).isEqualTo("google-sub-123");
        assertThat(saved.getProviderEmail()).isEqualTo("user@example.com");
        assertThat(saved.isProviderEmailVerified()).isTrue();
        assertThat(saved.getSuggestedFirstName()).isEqualTo("小明");
        assertThat(saved.getSuggestedLastName()).isEqualTo("王");

        // 首次註冊絕不直接建立 Account/Member——2026-08-22 定案修正
        verify(accounts, never()).save(any());
    }

    @Test
    void login_Email正規化_大小寫與前後空白不影響比對() {
        when(oauthAccounts.findAccountIdByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        GoogleIdentity mixedCaseEmail = new GoogleIdentity(
                "google-sub-123", "  User@Example.com  ", true, "小明", "王", null);
        when(accounts.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn("raw-registration-token");
        when(properties.registrationTicketTtl()).thenReturn(Duration.ofMinutes(15));

        service.login(mixedCaseEmail, null);

        verify(accounts).findByEmailIgnoreCase("user@example.com");
        ArgumentCaptor<OAuthRegistrationTicket> captor = ArgumentCaptor.forClass(OAuthRegistrationTicket.class);
        verify(registrationTickets).save(captor.capture());
        assertThat(captor.getValue().getProviderEmail()).isEqualTo("user@example.com");
    }

    private Account accountOf() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("user@example.com");
        return account;
    }

    private OauthAccount boundTo(Account account) {
        OauthAccount binding = new OauthAccount();
        binding.setAccount(account);
        return binding;
    }

    private GoogleIdentity identity() {
        return new GoogleIdentity("google-sub-123", "user@example.com", true, "小明", "王", null);
    }
}
