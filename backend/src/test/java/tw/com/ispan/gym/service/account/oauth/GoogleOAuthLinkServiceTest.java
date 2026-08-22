package tw.com.ispan.gym.service.account.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OauthAccount;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthRedirectReason;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.security.AccountLoginPolicy;
import tw.com.ispan.gym.security.oauth.GoogleIdentity;

/**
 * 純 Mockito 單元測試：Callback 階段對已通過發起端檢查（密碼、是否已綁定）的
 * transaction 做最後一道防禦性複查——重點在「同一 Google sub 不得綁定第二個
 * Account」與「Google email 必須與 Account email 一致」這兩條 v1 保守規則。
 */
class GoogleOAuthLinkServiceTest {

    private static final Integer ACCOUNT_ID = 30;
    private static final Integer OTHER_ACCOUNT_ID = 99;

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final OauthAccountRepository oauthAccounts = mock(OauthAccountRepository.class);
    private final AccountLoginPolicy loginPolicy = mock(AccountLoginPolicy.class);
    private final Clock clock = Clock.systemDefaultZone();

    private final GoogleOAuthLinkService service =
            new GoogleOAuthLinkService(accounts, oauthAccounts, loginPolicy, clock);

    @Test
    void link_Account不存在_拒絕AccountDisabled() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        GoogleOAuthLinkService.Outcome outcome = service.link(ACCOUNT_ID, matchingIdentity());

        assertThat(outcome).isInstanceOf(GoogleOAuthLinkService.Rejected.class);
        assertThat(((GoogleOAuthLinkService.Rejected) outcome).reason()).isEqualTo(OAuthRedirectReason.ACCOUNT_DISABLED);
        verify(oauthAccounts, never()).save(any());
    }

    @Test
    void link_Policy不允許登入_拒絕AccountDisabled() {
        Account account = accountOf(ACCOUNT_ID, "user@example.com");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(false);

        GoogleOAuthLinkService.Outcome outcome = service.link(ACCOUNT_ID, matchingIdentity());

        assertThat(outcome).isInstanceOf(GoogleOAuthLinkService.Rejected.class);
        assertThat(((GoogleOAuthLinkService.Rejected) outcome).reason()).isEqualTo(OAuthRedirectReason.ACCOUNT_DISABLED);
    }

    /** 對應必補案例「防止同一 Google 帳號綁定多個 Account」 */
    @Test
    void link_GoogleSub已綁定其他Account_拒絕IdentityInUse_不洩漏對方資訊() {
        Account account = accountOf(ACCOUNT_ID, "user@example.com");
        Account otherAccount = accountOf(OTHER_ACCOUNT_ID, "other@example.com");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(otherAccount));

        GoogleOAuthLinkService.Outcome outcome = service.link(ACCOUNT_ID, matchingIdentity());

        assertThat(outcome).isInstanceOf(GoogleOAuthLinkService.Rejected.class);
        assertThat(((GoogleOAuthLinkService.Rejected) outcome).reason()).isEqualTo(OAuthRedirectReason.IDENTITY_IN_USE);
        verify(oauthAccounts, never()).save(any());
    }

    @Test
    void link_GoogleSub已綁定同一Account_視為AlreadyLinked_不是IdentityInUse() {
        Account account = accountOf(ACCOUNT_ID, "user@example.com");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        // sub 綁定的對象就是自己這個 accountId——不屬於「被別人搶先」
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(account));
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(new OauthAccount()));

        GoogleOAuthLinkService.Outcome outcome = service.link(ACCOUNT_ID, matchingIdentity());

        assertThat(((GoogleOAuthLinkService.Rejected) outcome).reason()).isEqualTo(OAuthRedirectReason.ALREADY_LINKED);
    }

    @Test
    void link_Account已綁定另一個GoogleSub_拒絕AlreadyLinked() {
        Account account = accountOf(ACCOUNT_ID, "user@example.com");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(new OauthAccount()));

        GoogleOAuthLinkService.Outcome outcome = service.link(ACCOUNT_ID, matchingIdentity());

        assertThat(((GoogleOAuthLinkService.Rejected) outcome).reason()).isEqualTo(OAuthRedirectReason.ALREADY_LINKED);
        verify(oauthAccounts, never()).save(any());
    }

    @Test
    void link_EmailNull_拒絕LinkEmailMismatch() {
        Account account = accountOf(ACCOUNT_ID, "user@example.com");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.empty());
        GoogleIdentity noEmail = new GoogleIdentity("google-sub-123", null, false, null, null, null);

        GoogleOAuthLinkService.Outcome outcome = service.link(ACCOUNT_ID, noEmail);

        assertThat(((GoogleOAuthLinkService.Rejected) outcome).reason())
                .isEqualTo(OAuthRedirectReason.LINK_EMAIL_MISMATCH);
    }

    @Test
    void link_Email未驗證_拒絕LinkEmailMismatch() {
        Account account = accountOf(ACCOUNT_ID, "user@example.com");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.empty());
        GoogleIdentity unverified = new GoogleIdentity("google-sub-123", "user@example.com", false, null, null, null);

        GoogleOAuthLinkService.Outcome outcome = service.link(ACCOUNT_ID, unverified);

        assertThat(((GoogleOAuthLinkService.Rejected) outcome).reason())
                .isEqualTo(OAuthRedirectReason.LINK_EMAIL_MISMATCH);
    }

    /** 對應必補案例「Google Email 必須符合 Account Email」 */
    @Test
    void link_Email與AccountEmail不一致_拒絕LinkEmailMismatch() {
        Account account = accountOf(ACCOUNT_ID, "user@example.com");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.empty());
        GoogleIdentity differentEmail =
                new GoogleIdentity("google-sub-123", "someone-else@example.com", true, null, null, null);

        GoogleOAuthLinkService.Outcome outcome = service.link(ACCOUNT_ID, differentEmail);

        assertThat(((GoogleOAuthLinkService.Rejected) outcome).reason())
                .isEqualTo(OAuthRedirectReason.LINK_EMAIL_MISMATCH);
        verify(oauthAccounts, never()).save(any());
    }

    @Test
    void link_全部條件符合_建立綁定並回傳Linked() {
        Account account = accountOf(ACCOUNT_ID, "  User@Example.com  ");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.empty());
        // 大小寫與前後空白不影響比對
        GoogleIdentity identity = new GoogleIdentity("google-sub-123", "user@example.com", true, "小明", "王", null);

        GoogleOAuthLinkService.Outcome outcome = service.link(ACCOUNT_ID, identity);

        assertThat(outcome).isInstanceOf(GoogleOAuthLinkService.Linked.class);
        ArgumentCaptor<OauthAccount> captor = ArgumentCaptor.forClass(OauthAccount.class);
        verify(oauthAccounts).save(captor.capture());
        OauthAccount saved = captor.getValue();
        assertThat(saved.getAccount()).isSameAs(account);
        assertThat(saved.getOauthProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(saved.getProviderUserId()).isEqualTo("google-sub-123");
        assertThat(saved.getProviderEmailVerified()).isTrue();
        assertThat(saved.getLastLoginAt()).isNotNull();
    }

    private Account accountOf(Integer id, String email) {
        Account account = new Account();
        account.setId(id);
        account.setEmail(email);
        return account;
    }

    private GoogleIdentity matchingIdentity() {
        return new GoogleIdentity("google-sub-123", "user@example.com", true, "小明", "王", null);
    }
}
