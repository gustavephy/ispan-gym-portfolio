package tw.com.ispan.gym.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * app.oauth.* 設定集中一處，供 Resolver／Handler／Service 共用，避免到處重複
 * {@code @Value} 注入同一組設定。
 */
@Component
public class GoogleOAuthProperties {

    private final boolean enabled;
    private final String frontendSuccessUrl;
    private final String frontendRegistrationUrl;
    private final String frontendLinkResultUrl;
    private final String frontendErrorUrl;
    private final Duration transactionTtl;
    private final Duration registrationTicketTtl;
    private final Duration exchangeCodeTtl;

    public GoogleOAuthProperties(
            @Value("${app.oauth.google.enabled}") boolean enabled,
            @Value("${app.oauth.frontend-success-url}") String frontendSuccessUrl,
            @Value("${app.oauth.frontend-registration-url}") String frontendRegistrationUrl,
            @Value("${app.oauth.frontend-link-result-url}") String frontendLinkResultUrl,
            @Value("${app.oauth.frontend-error-url}") String frontendErrorUrl,
            @Value("${app.oauth.transaction-ttl-minutes}") long transactionTtlMinutes,
            @Value("${app.oauth.registration-ticket-ttl-minutes}") long registrationTicketTtlMinutes,
            @Value("${app.oauth.exchange-code-ttl-seconds}") long exchangeCodeTtlSeconds) {
        this.enabled = enabled;
        this.frontendSuccessUrl = frontendSuccessUrl;
        this.frontendRegistrationUrl = frontendRegistrationUrl;
        this.frontendLinkResultUrl = frontendLinkResultUrl;
        this.frontendErrorUrl = frontendErrorUrl;
        this.transactionTtl = Duration.ofMinutes(transactionTtlMinutes);
        this.registrationTicketTtl = Duration.ofMinutes(registrationTicketTtlMinutes);
        this.exchangeCodeTtl = Duration.ofSeconds(exchangeCodeTtlSeconds);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String frontendSuccessUrl() {
        return frontendSuccessUrl;
    }

    public String frontendRegistrationUrl() {
        return frontendRegistrationUrl;
    }

    public String frontendLinkResultUrl() {
        return frontendLinkResultUrl;
    }

    public String frontendErrorUrl() {
        return frontendErrorUrl;
    }

    public Duration transactionTtl() {
        return transactionTtl;
    }

    public Duration registrationTicketTtl() {
        return registrationTicketTtl;
    }

    public Duration exchangeCodeTtl() {
        return exchangeCodeTtl;
    }
}
