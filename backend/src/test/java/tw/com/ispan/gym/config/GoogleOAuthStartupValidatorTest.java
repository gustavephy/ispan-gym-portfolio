package tw.com.ispan.gym.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * 純 Mockito 單元測試：對應 step 3「讓 feature flag 真正生效」的第 3 項——
 * {@code enabled=true} 卻設定有誤時，啟動必須 fail fast，不能悄悄用一個不能真的
 * 登入的設定跑起來。{@code enabled=false} 時完全不檢查，這是既有「本機沒有真實
 * 憑證仍可正常開機」保證的另一半。
 */
class GoogleOAuthStartupValidatorTest {

    private final GoogleOAuthProperties properties = mock(GoogleOAuthProperties.class);
    private final ClientRegistrationRepository clientRegistrations = mock(ClientRegistrationRepository.class);

    private final GoogleOAuthStartupValidator validator =
            new GoogleOAuthStartupValidator(properties, clientRegistrations);

    @Test
    void run_停用時完全不檢查_也不查ClientRegistration() {
        when(properties.isEnabled()).thenReturn(false);

        assertThatCode(() -> validator.run()).doesNotThrowAnyException();

        verify(clientRegistrations, never()).findByRegistrationId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void run_啟用但找不到GoogleClientRegistration_丟IllegalState() {
        when(properties.isEnabled()).thenReturn(true);
        when(clientRegistrations.findByRegistrationId("google")).thenReturn(null);
        stubValidFrontendUrls();

        assertThatThrownBy(() -> validator.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("找不到");
    }

    @Test
    void run_啟用但ClientId仍是Placeholder_丟IllegalState() {
        when(properties.isEnabled()).thenReturn(true);
        when(clientRegistrations.findByRegistrationId("google"))
                .thenReturn(registrationOf("unset-client-id", "real-secret"));
        stubValidFrontendUrls();

        assertThatThrownBy(() -> validator.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_CLIENT_ID");
    }

    @Test
    void run_啟用但ClientSecret仍是Placeholder_丟IllegalState() {
        when(properties.isEnabled()).thenReturn(true);
        when(clientRegistrations.findByRegistrationId("google"))
                .thenReturn(registrationOf("real-client-id", "unset-client-secret"));
        stubValidFrontendUrls();

        assertThatThrownBy(() -> validator.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_CLIENT_SECRET");
    }

    @Test
    void run_前端Url未設定_丟IllegalState() {
        when(properties.isEnabled()).thenReturn(true);
        when(clientRegistrations.findByRegistrationId("google"))
                .thenReturn(registrationOf("real-client-id", "real-secret"));
        when(properties.frontendSuccessUrl()).thenReturn("");
        when(properties.frontendRegistrationUrl()).thenReturn("https://frontend.example/oauth/register");
        when(properties.frontendLinkResultUrl()).thenReturn("https://frontend.example/account/oauth/callback");
        when(properties.frontendErrorUrl()).thenReturn("https://frontend.example/login");

        assertThatThrownBy(() -> validator.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.oauth.frontend-success-url");
    }

    @Test
    void run_前端Url不是合法URI_丟IllegalState() {
        when(properties.isEnabled()).thenReturn(true);
        when(clientRegistrations.findByRegistrationId("google"))
                .thenReturn(registrationOf("real-client-id", "real-secret"));
        when(properties.frontendSuccessUrl()).thenReturn("not a url with spaces and no scheme");
        when(properties.frontendRegistrationUrl()).thenReturn("https://frontend.example/oauth/register");
        when(properties.frontendLinkResultUrl()).thenReturn("https://frontend.example/account/oauth/callback");
        when(properties.frontendErrorUrl()).thenReturn("https://frontend.example/login");

        assertThatThrownBy(() -> validator.run()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void run_前端Url是javascript協定_丟IllegalState() {
        when(properties.isEnabled()).thenReturn(true);
        when(clientRegistrations.findByRegistrationId("google"))
                .thenReturn(registrationOf("real-client-id", "real-secret"));
        when(properties.frontendSuccessUrl()).thenReturn("javascript:alert(1)");
        when(properties.frontendRegistrationUrl()).thenReturn("https://frontend.example/oauth/register");
        when(properties.frontendLinkResultUrl()).thenReturn("https://frontend.example/account/oauth/callback");
        when(properties.frontendErrorUrl()).thenReturn("https://frontend.example/login");

        assertThatThrownBy(() -> validator.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http 或 https");
    }

    /** 對應必補案例「Production URL 使用 HTTPS」——指向非本機主機時不能是 http */
    @Test
    void run_前端Url指向非本機主機卻用Http_丟IllegalState() {
        when(properties.isEnabled()).thenReturn(true);
        when(clientRegistrations.findByRegistrationId("google"))
                .thenReturn(registrationOf("real-client-id", "real-secret"));
        when(properties.frontendSuccessUrl()).thenReturn("http://gym.example.com/oauth/callback");
        when(properties.frontendRegistrationUrl()).thenReturn("https://frontend.example/oauth/register");
        when(properties.frontendLinkResultUrl()).thenReturn("https://frontend.example/account/oauth/callback");
        when(properties.frontendErrorUrl()).thenReturn("https://frontend.example/login");

        assertThatThrownBy(() -> validator.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void run_本機HttpUrl允許_不需要HTTPS() {
        when(properties.isEnabled()).thenReturn(true);
        when(clientRegistrations.findByRegistrationId("google"))
                .thenReturn(registrationOf("real-client-id", "real-secret"));
        when(properties.frontendSuccessUrl()).thenReturn("http://localhost:5173/oauth/callback");
        when(properties.frontendRegistrationUrl()).thenReturn("http://127.0.0.1:5173/oauth/register");
        when(properties.frontendLinkResultUrl()).thenReturn("http://localhost:5173/account/oauth/callback");
        when(properties.frontendErrorUrl()).thenReturn("http://localhost:5173/login");

        assertThatCode(() -> validator.run()).doesNotThrowAnyException();
    }

    @Test
    void run_啟用且設定全部有效_不丟例外() {
        when(properties.isEnabled()).thenReturn(true);
        when(clientRegistrations.findByRegistrationId("google"))
                .thenReturn(registrationOf("real-client-id", "real-secret"));
        stubValidFrontendUrls();

        assertThatCode(() -> validator.run()).doesNotThrowAnyException();
    }

    /** 用真正的 builder 產生 ClientRegistration，而非直接 mock——它不是設計來被 mock 的簡單介面 */
    private ClientRegistration registrationOf(String clientId, String clientSecret) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .scope("openid", "email", "profile")
                .build();
        return new InMemoryClientRegistrationRepository(registration).findByRegistrationId("google");
    }

    private void stubValidFrontendUrls() {
        when(properties.frontendSuccessUrl()).thenReturn("https://frontend.example/oauth/callback");
        when(properties.frontendRegistrationUrl()).thenReturn("https://frontend.example/oauth/register");
        when(properties.frontendLinkResultUrl()).thenReturn("https://frontend.example/account/oauth/callback");
        when(properties.frontendErrorUrl()).thenReturn("https://frontend.example/login");
    }
}
