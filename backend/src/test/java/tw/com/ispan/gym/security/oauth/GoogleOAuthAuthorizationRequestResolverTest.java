package tw.com.ispan.gym.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.entity.account.OAuthTransaction;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthTransactionPurpose;
import tw.com.ispan.gym.enums.account.OAuthTransactionStatus;
import tw.com.ispan.gym.repository.account.OAuthTransactionRepository;
import tw.com.ispan.gym.repository.account.OAuthTransactionView;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 純 Mockito 單元測試（真正的 Google 端交握留給真實 Google E2E）：
 * LOGIN 一律建立新 transaction 並用它當 state；LINK 的 {@code transaction} 參數
 * 是唯讀驗證，不合法就必須在打 Google 之前直接中止（回傳 null），不能先建立
 * authorization request 才發現無效。
 */
class GoogleOAuthAuthorizationRequestResolverTest {

    private static final String LOGIN_PATH = "/oauth2/authorization/google";

    private final OAuthTransactionRepository transactions = mock(OAuthTransactionRepository.class);
    private final OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
    private final GoogleOAuthProperties properties = mock(GoogleOAuthProperties.class);

    private final GoogleOAuthAuthorizationRequestResolver resolver = new GoogleOAuthAuthorizationRequestResolver(
            clientRegistrations(), transactions, tokenGenerator, properties);

    @Test
    void resolve_Login無ReturnPath_建立新Transaction並以其State回傳() {
        when(tokenGenerator.generate()).thenReturn("raw-state");
        when(tokenGenerator.hash("raw-state")).thenReturn("hash-of-state");
        when(properties.transactionTtl()).thenReturn(Duration.ofMinutes(10));

        OAuth2AuthorizationRequest result = resolver.resolve(loginRequest(null));

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo("raw-state");

        ArgumentCaptor<OAuthTransaction> captor = ArgumentCaptor.forClass(OAuthTransaction.class);
        verify(transactions).save(captor.capture());
        OAuthTransaction saved = captor.getValue();
        assertThat(saved.getStateHash()).isEqualTo("hash-of-state");
        assertThat(saved.getOauthProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(saved.getPurpose()).isEqualTo(OAuthTransactionPurpose.LOGIN);
        assertThat(saved.getReturnPath()).isNull();
        assertThat(saved.getAccount()).isNull();
    }

    @Test
    void resolve_Login帶合法ReturnPath_儲存已驗證過的相對路徑() {
        when(tokenGenerator.generate()).thenReturn("raw-state");
        when(properties.transactionTtl()).thenReturn(Duration.ofMinutes(10));

        resolver.resolve(loginRequest("/orders"));

        ArgumentCaptor<OAuthTransaction> captor = ArgumentCaptor.forClass(OAuthTransaction.class);
        verify(transactions).save(captor.capture());
        assertThat(captor.getValue().getReturnPath()).isEqualTo("/orders");
    }

    /** 對應必補案例「Return path 拒絕 absolute URL、protocol-relative URL 與控制字元」 */
    @Test
    void resolve_Login帶不合法ReturnPath_儲存為Null_不阻擋整個流程() {
        when(tokenGenerator.generate()).thenReturn("raw-state");
        when(properties.transactionTtl()).thenReturn(Duration.ofMinutes(10));

        resolver.resolve(loginRequest("https://evil.example"));

        ArgumentCaptor<OAuthTransaction> captor = ArgumentCaptor.forClass(OAuthTransaction.class);
        verify(transactions).save(captor.capture());
        assertThat(captor.getValue().getReturnPath()).isNull();
    }

    @Test
    void resolve_Link帶有效Transaction_直接沿用不建立新Transaction() {
        MockHttpServletRequest request = loginRequest(null);
        request.addParameter("transaction", "raw-link-transaction");
        when(tokenGenerator.hash("raw-link-transaction")).thenReturn("hash-of-link-transaction");
        when(transactions.findViewByStateHash("hash-of-link-transaction")).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.PENDING, OAuthTransactionPurpose.LINK, LocalDateTime.now().plusMinutes(5))));

        OAuth2AuthorizationRequest result = resolver.resolve(request);

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo("raw-link-transaction");
        // LINK 沿用既有 transaction，不應該再建立一筆新的
        verify(transactions, never()).save(any());
    }

    @Test
    void resolve_Link的Transaction不存在時直接中止_回傳Null不打Google() {
        MockHttpServletRequest request = loginRequest(null);
        request.addParameter("transaction", "raw-link-transaction");
        when(tokenGenerator.hash("raw-link-transaction")).thenReturn("hash-of-link-transaction");
        when(transactions.findViewByStateHash("hash-of-link-transaction")).thenReturn(Optional.empty());

        OAuth2AuthorizationRequest result = resolver.resolve(request);

        assertThat(result).isNull();
        verify(transactions, never()).save(any());
    }

    @Test
    void resolve_Link的Transaction已過期時中止() {
        MockHttpServletRequest request = loginRequest(null);
        request.addParameter("transaction", "raw-link-transaction");
        when(tokenGenerator.hash("raw-link-transaction")).thenReturn("hash-of-link-transaction");
        when(transactions.findViewByStateHash("hash-of-link-transaction")).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.PENDING, OAuthTransactionPurpose.LINK, LocalDateTime.now().minusMinutes(1))));

        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void resolve_Link的Transaction已消耗時中止() {
        MockHttpServletRequest request = loginRequest(null);
        request.addParameter("transaction", "raw-link-transaction");
        when(tokenGenerator.hash("raw-link-transaction")).thenReturn("hash-of-link-transaction");
        when(transactions.findViewByStateHash("hash-of-link-transaction")).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.CONSUMED, OAuthTransactionPurpose.LINK, LocalDateTime.now().plusMinutes(5))));

        assertThat(resolver.resolve(request)).isNull();
    }

    /** purpose 不符（誤把 LOGIN transaction 的 state 拿來當 LINK capability 用）也要中止 */
    @Test
    void resolve_Link但Transaction的Purpose是Login時中止() {
        MockHttpServletRequest request = loginRequest(null);
        request.addParameter("transaction", "raw-login-state");
        when(tokenGenerator.hash("raw-login-state")).thenReturn("hash-of-login-state");
        when(transactions.findViewByStateHash("hash-of-login-state")).thenReturn(Optional.of(
                viewOf(OAuthTransactionStatus.PENDING, OAuthTransactionPurpose.LOGIN, LocalDateTime.now().plusMinutes(5))));

        assertThat(resolver.resolve(request)).isNull();
    }

    private MockHttpServletRequest loginRequest(String returnPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", LOGIN_PATH);
        request.setServletPath(LOGIN_PATH);
        if (returnPath != null) {
            request.addParameter("returnPath", returnPath);
        }
        return request;
    }

    private OAuthTransactionView viewOf(OAuthTransactionStatus status, OAuthTransactionPurpose purpose,
            LocalDateTime expiresAt) {
        return new OAuthTransactionView("hash-of-link-transaction", OAuthProvider.GOOGLE, purpose, status,
                null, null, expiresAt);
    }

    private InMemoryClientRegistrationRepository clientRegistrations() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .clientSecret("test-client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .scope("openid", "email", "profile")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }
}
