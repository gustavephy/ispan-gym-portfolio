package tw.com.ispan.gym.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import jakarta.servlet.http.HttpSession;
import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.repository.account.OAuthTransactionRepository;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 純 Mockito 單元測試：對應 P3 修正——只驗證行為層面（分類正確、白名單 reason、
 * 標記失敗、安全 header），不驗證 log 內容本身（log 是否洩漏原始 message 已經在
 * P3 review 時對照原始碼確認過，執行期斷言 log 沒印什麼，價值有限）。
 */
class GoogleOAuthFailureHandlerTest {

    private static final String RAW_STATE = "raw-state";
    private static final String STATE_HASH = "hash-of-state";
    private static final String ERROR_URL = "https://frontend.example/login";

    private final OAuthTransactionRepository transactions = mock(OAuthTransactionRepository.class);
    private final OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
    private final GoogleOAuthProperties properties = mock(GoogleOAuthProperties.class);

    private final Clock clock = Clock.systemDefaultZone();

    private final GoogleOAuthFailureHandler handler =
            new GoogleOAuthFailureHandler(transactions, tokenGenerator, properties, clock);

    @Test
    void onFailure_使用者取消Google授權_導向AccessDenied() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("error", "access_denied");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("ignored"));

        assertThat(response.getRedirectedUrl()).isEqualTo(ERROR_URL + "?oauthError=access_denied");
    }

    @Test
    void onFailure_OAuth2協定例外_導向ProviderError() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                new OAuth2Error("invalid_request"), "Google 回傳的詳細錯誤內容，不該出現在 log 或 URL 裡");

        handler.onAuthenticationFailure(request, response, ex);

        assertThat(response.getRedirectedUrl()).isEqualTo(ERROR_URL + "?oauthError=provider_error");
        // 白名單 reason 不得含有原始 exception message 的任何片段
        assertThat(response.getRedirectedUrl()).doesNotContain("Google 回傳的詳細錯誤內容");
    }

    @Test
    void onFailure_非預期例外_導向UnexpectedError() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("非預期"));

        assertThat(response.getRedirectedUrl()).isEqualTo(ERROR_URL + "?oauthError=unexpected_error");
    }

    @Test
    void onFailure_State存在時標記Transaction失敗() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        when(tokenGenerator.hash(RAW_STATE)).thenReturn(STATE_HASH);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("state", RAW_STATE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("x"));

        verify(transactions).markFailedIfPending(org.mockito.ArgumentMatchers.eq(STATE_HASH), any(),
                org.mockito.ArgumentMatchers.eq("UNEXPECTED_ERROR"));
    }

    @Test
    void onFailure_State不存在時不標記Transaction() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("x"));

        verify(transactions, never()).markFailedIfPending(anyString(), any(), anyString());
    }

    @Test
    void onFailure_回應帶NoStore與NoReferrerHeader() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("x"));

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    /** 對應 step 3「Callback 成功或失敗後清除 OAuth Session」 */
    @Test
    void onFailure_會清除HttpSession() throws Exception {
        when(properties.frontendErrorUrl()).thenReturn(ERROR_URL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession(true);

        handler.onAuthenticationFailure(request, new MockHttpServletResponse(), new BadCredentialsException("x"));

        assertThat(((MockHttpSession) session).isInvalid()).isTrue();
    }
}
