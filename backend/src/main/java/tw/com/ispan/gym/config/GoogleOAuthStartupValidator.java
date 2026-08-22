package tw.com.ispan.gym.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * {@code app.oauth.google.enabled=false} 時完全不檢查——功能沒開，設定對不對無所謂，
 * 應用照常啟動（見 {@link GoogleOAuthProperties} 與 application.properties 的說明）。
 *
 * <p>{@code enabled=true} 時才檢查：Google client id／secret 是不是還停在 placeholder
 * 預設值（代表宣告啟用但忘了真的設定環境變數）、四個前端 URL 是否為合法 http/https
 * 網址且帶有效主機名稱（opaque URI 如 {@code https:foo} 語法合法但沒有 host，一併擋下）、
 * 指向非本機主機時必須是 HTTPS、三組 TTL 是否都大於零。任何一項不符就丟例外讓啟動失敗
 * ——「宣告啟用卻設定錯誤」不該讓功能悄悄不能用，必須在啟動當下就發現。
 *
 * <p>刻意排在 {@link SchemaFixupRunner}（{@code @Order(0)}）之前：設定本身就是錯的，
 * 不需要先浪費時間跑一輪 schema migration 才失敗。
 */
@Component
@Order(-1)
public class GoogleOAuthStartupValidator implements CommandLineRunner {

    private static final String PLACEHOLDER_CLIENT_ID = "unset-client-id";
    private static final String PLACEHOLDER_CLIENT_SECRET = "unset-client-secret";
    private static final String GOOGLE_REGISTRATION_ID = "google";

    private final GoogleOAuthProperties properties;
    private final ClientRegistrationRepository clientRegistrations;

    public GoogleOAuthStartupValidator(GoogleOAuthProperties properties,
            ClientRegistrationRepository clientRegistrations) {
        this.properties = properties;
        this.clientRegistrations = clientRegistrations;
    }

    @Override
    public void run(String... args) {
        if (!properties.isEnabled()) {
            return;
        }

        List<String> problems = new ArrayList<>();
        validateClientRegistration(problems);
        validateFrontendUrl("app.oauth.frontend-success-url", properties.frontendSuccessUrl(), problems);
        validateFrontendUrl("app.oauth.frontend-registration-url", properties.frontendRegistrationUrl(), problems);
        validateFrontendUrl("app.oauth.frontend-link-result-url", properties.frontendLinkResultUrl(), problems);
        validateFrontendUrl("app.oauth.frontend-error-url", properties.frontendErrorUrl(), problems);
        validatePositiveTtl("app.oauth.transaction-ttl-minutes", properties.transactionTtl(), problems);
        validatePositiveTtl("app.oauth.registration-ticket-ttl-minutes", properties.registrationTicketTtl(), problems);
        validatePositiveTtl("app.oauth.exchange-code-ttl-seconds", properties.exchangeCodeTtl(), problems);

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "app.oauth.google.enabled=true，但 Google OAuth 設定有誤，啟動中止：\n - "
                            + String.join("\n - ", problems));
        }
    }

    private void validateClientRegistration(List<String> problems) {
        ClientRegistration registration = clientRegistrations.findByRegistrationId(GOOGLE_REGISTRATION_ID);
        if (registration == null) {
            problems.add("找不到 registrationId=google 的 ClientRegistration");
            return;
        }
        if (isBlankOrPlaceholder(registration.getClientId(), PLACEHOLDER_CLIENT_ID)) {
            problems.add("GOOGLE_CLIENT_ID 未設定（仍是預設 placeholder 值）");
        }
        if (isBlankOrPlaceholder(registration.getClientSecret(), PLACEHOLDER_CLIENT_SECRET)) {
            problems.add("GOOGLE_CLIENT_SECRET 未設定（仍是預設 placeholder 值）");
        }
    }

    private void validateFrontendUrl(String propertyName, String rawUrl, List<String> problems) {
        if (rawUrl == null || rawUrl.isBlank()) {
            problems.add(propertyName + " 未設定");
            return;
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException ex) {
            problems.add(propertyName + " 不是合法的 URL：" + rawUrl);
            return;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            problems.add(propertyName + " 必須是 http 或 https，實際：" + rawUrl);
            return;
        }
        // opaque URI（如 "https:foo"）語法上合法、scheme 也對，但沒有 authority／host，
        // uri.getHost() 會是 null；下面的 isLocal 判斷若不先擋掉這種情況，null 既不等於
        // "localhost" 也不等於 "127.0.0.1"，會被誤判成「非本機」再去檢查 scheme 是不是
        // https——而這裡 scheme 剛好就是 https，於是整個 opaque URI 就這樣矇混過關
        if (uri.isOpaque() || uri.getHost() == null || uri.getHost().isBlank()) {
            problems.add(propertyName + " 缺少有效主機名稱：" + rawUrl);
            return;
        }
        String host = uri.getHost();
        boolean isLocal = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
        if (!isLocal && !"https".equalsIgnoreCase(scheme)) {
            problems.add(propertyName + " 指向非本機主機時必須使用 HTTPS，實際：" + rawUrl);
        }
    }

    private void validatePositiveTtl(String propertyName, Duration ttl, List<String> problems) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            problems.add(propertyName + " 必須大於零，實際：" + ttl);
        }
    }

    private boolean isBlankOrPlaceholder(String value, String placeholder) {
        return value == null || value.isBlank() || value.equals(placeholder);
    }
}
