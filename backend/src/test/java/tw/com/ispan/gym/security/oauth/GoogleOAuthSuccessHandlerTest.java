package tw.com.ispan.gym.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthRedirectReason;
import tw.com.ispan.gym.enums.account.OAuthTransactionPurpose;
import tw.com.ispan.gym.enums.account.OAuthTransactionStatus;
import tw.com.ispan.gym.repository.account.OAuthTransactionRepository;
import tw.com.ispan.gym.repository.account.OAuthTransactionView;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthLinkService;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthLoginService;

/**
 * 純 Mockito 單元測試：Success Handler 必須依 server-side transaction 分流，
 * 且成功／失敗 Redirect 只能帶白名單資訊，絕不含 JWT／Email／Account ID。
 */
class GoogleOAuthSuccessHandlerTest {

    private static final String RAW_STATE = "raw-state";
    private static final String STATE_HASH = "hash-of-state";
    private static final String SUCCESS_URL = "https://frontend.example/oauth/callback";
    private static final String REGISTRATION_URL = "https://frontend.example/oauth/registration";
    private static final String LINK_RESULT_URL = "https://frontend.example/account/oauth/callback";
    private static final String ERROR_URL = "https://frontend.example/login";

    private final OAuthTransactionRepository transactions = mock(OAuthTransactionRepository.class);
    private final OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
    private final GoogleIdentityMapper identityMapper = mock(GoogleIdentityMapper.class);
    private final GoogleOAuthLoginService loginService = mock(GoogleOAuthLoginService.class);
    private final GoogleOAuthLinkService linkService = mock(GoogleOAuthLinkService.class);
    private final GoogleOAuthProperties properties = mock(GoogleOAuthProperties.class);

    private final GoogleOAuthSuccessHandler handler = new GoogleOAuthSuccessHandler(
            transactions, tokenGenerator, identityMapper, loginService, linkService, properties);

    @Test
    void onSuccess_State缺失_導向FlowExpired_不查Transaction() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(ERROR_URL + "?oauthError=flow_expired");
        verify(transactions, never()).findViewByStateHash(anyString());
        verify(transactions, never()).markFailedIfPending(anyString(), any(), anyString());
    }

    @Test
    void onSuccess_Transaction查不到_導向FlowExpired() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        when(tokenGenerator.hash(RAW_STATE)).thenReturn(STATE_HASH);
        when(transactions.findViewByStateHash(STATE_HASH)).thenReturn(Optional.empty());

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(stateRequest(), response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(ERROR_URL + "?oauthError=flow_expired");
        // 查不到就不知道要標記哪一列，不能呼叫 markFailedIfPending
        verify(transactions, never()).markFailedIfPending(anyString(), any(), anyString());
    }

    @Test
    void onSuccess_Transaction已SupersededByNewFlow_導向FlowReplaced_不是通用錯誤() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        when(tokenGenerator.hash(RAW_STATE)).thenReturn(STATE_HASH);
        when(transactions.findViewByStateHash(STATE_HASH)).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.SUPERSEDED, OAuthTransactionPurpose.LOGIN, null,
                        LocalDateTime.now().plusMinutes(5))));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(stateRequest(), response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(ERROR_URL + "?oauthError=flow_replaced");
    }

    @Test
    void onSuccess_Transaction已過期_導向FlowExpired() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        when(tokenGenerator.hash(RAW_STATE)).thenReturn(STATE_HASH);
        when(transactions.findViewByStateHash(STATE_HASH)).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.PENDING, OAuthTransactionPurpose.LOGIN, null,
                        LocalDateTime.now().minusMinutes(1))));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(stateRequest(), response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(ERROR_URL + "?oauthError=flow_expired");
    }

    /** 對應必補案例「防止併發 Callback 重複執行」：原子消耗回傳 0 列必須直接中止 */
    @Test
    void onSuccess_原子消耗失敗_導向FlowExpired_不呼叫LoginService() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        when(tokenGenerator.hash(RAW_STATE)).thenReturn(STATE_HASH);
        when(transactions.findViewByStateHash(STATE_HASH)).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.PENDING, OAuthTransactionPurpose.LOGIN, null,
                        LocalDateTime.now().plusMinutes(5))));
        when(transactions.consumeIfPending(eq(STATE_HASH), any())).thenReturn(0);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(stateRequest(), response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(ERROR_URL + "?oauthError=flow_expired");
        verify(loginService, never()).login(any(), any());
    }

    @Test
    void onSuccess_Login成功_導向SuccessUrl帶Code_不含JWT或Email() throws Exception {
        when(tokenGenerator.hash(RAW_STATE)).thenReturn(STATE_HASH);
        when(transactions.findViewByStateHash(STATE_HASH)).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.PENDING, OAuthTransactionPurpose.LOGIN, null,
                        LocalDateTime.now().plusMinutes(5))));
        when(transactions.consumeIfPending(eq(STATE_HASH), any())).thenReturn(1);
        GoogleIdentity identity = new GoogleIdentity("google-sub-123", "user@example.com", true, "小明", "王", null);
        when(identityMapper.map(any())).thenReturn(identity);
        when(loginService.login(eq(identity), any()))
                .thenReturn(new GoogleOAuthLoginService.Authenticated("raw-exchange-code"));
        when(properties.frontendSuccessUrl()).thenReturn(SUCCESS_URL);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(stateRequest(), response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(SUCCESS_URL + "?code=raw-exchange-code");
        assertThat(response.getRedirectedUrl()).doesNotContain("jwt", "token=", "accountId", "@example.com");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void onSuccess_首次登入需補資料_導向RegistrationUrl帶Token() throws Exception {
        when(tokenGenerator.hash(RAW_STATE)).thenReturn(STATE_HASH);
        when(transactions.findViewByStateHash(STATE_HASH)).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.PENDING, OAuthTransactionPurpose.LOGIN, null,
                        LocalDateTime.now().plusMinutes(5))));
        when(transactions.consumeIfPending(eq(STATE_HASH), any())).thenReturn(1);
        when(identityMapper.map(any())).thenReturn(identity());
        when(loginService.login(any(), any()))
                .thenReturn(new GoogleOAuthLoginService.RegistrationRequired("raw-registration-token"));
        when(properties.frontendRegistrationUrl()).thenReturn(REGISTRATION_URL);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(stateRequest(), response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(REGISTRATION_URL + "?token=raw-registration-token");
    }

    @Test
    void onSuccess_Login被拒絕_導向ErrorUrl帶白名單Reason() throws Exception {
        when(tokenGenerator.hash(RAW_STATE)).thenReturn(STATE_HASH);
        when(transactions.findViewByStateHash(STATE_HASH)).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.PENDING, OAuthTransactionPurpose.LOGIN, null,
                        LocalDateTime.now().plusMinutes(5))));
        when(transactions.consumeIfPending(eq(STATE_HASH), any())).thenReturn(1);
        when(identityMapper.map(any())).thenReturn(identity());
        when(loginService.login(any(), any()))
                .thenReturn(new GoogleOAuthLoginService.Rejected(OAuthRedirectReason.ACCOUNT_LINK_REQUIRED));
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(stateRequest(), response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(ERROR_URL + "?oauthError=account_link_required");
    }

    @Test
    void onSuccess_Link成功_導向LinkResultUrl_不簽發JWT() throws Exception {
        Integer accountId = 30;
        when(tokenGenerator.hash(RAW_STATE)).thenReturn(STATE_HASH);
        when(transactions.findViewByStateHash(STATE_HASH)).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.PENDING, OAuthTransactionPurpose.LINK, accountId,
                        LocalDateTime.now().plusMinutes(5))));
        when(transactions.consumeIfPending(eq(STATE_HASH), any())).thenReturn(1);
        GoogleIdentity identity = identity();
        when(identityMapper.map(any())).thenReturn(identity);
        when(linkService.link(accountId, identity)).thenReturn(new GoogleOAuthLinkService.Linked());
        when(properties.frontendLinkResultUrl()).thenReturn(LINK_RESULT_URL);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(stateRequest(), response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(LINK_RESULT_URL + "?result=linked");
        verify(loginService, never()).login(any(), any());
    }

    @Test
    void onSuccess_Link被拒絕_導向ErrorUrl() throws Exception {
        Integer accountId = 30;
        when(tokenGenerator.hash(RAW_STATE)).thenReturn(STATE_HASH);
        when(transactions.findViewByStateHash(STATE_HASH)).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.PENDING, OAuthTransactionPurpose.LINK, accountId,
                        LocalDateTime.now().plusMinutes(5))));
        when(transactions.consumeIfPending(eq(STATE_HASH), any())).thenReturn(1);
        when(identityMapper.map(any())).thenReturn(identity());
        when(linkService.link(eq(accountId), any()))
                .thenReturn(new GoogleOAuthLinkService.Rejected(OAuthRedirectReason.IDENTITY_IN_USE));
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(stateRequest(), response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(ERROR_URL + "?oauthError=identity_in_use");
    }

    private MockHttpServletRequest stateRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("state", RAW_STATE);
        return request;
    }

    private OAuth2AuthenticationToken authentication() {
        OidcUser oidcUser = mock(OidcUser.class);
        return new OAuth2AuthenticationToken(oidcUser, List.of(), "google");
    }

    private GoogleIdentity identity() {
        return new GoogleIdentity("google-sub-123", "user@example.com", true, "小明", "王", null);
    }

    private OAuthTransactionView viewOf(OAuthTransactionStatus status, OAuthTransactionPurpose purpose,
            Integer accountId, LocalDateTime expiresAt) {
        return new OAuthTransactionView(STATE_HASH, OAuthProvider.GOOGLE, purpose, status, accountId, null, expiresAt);
    }
}
