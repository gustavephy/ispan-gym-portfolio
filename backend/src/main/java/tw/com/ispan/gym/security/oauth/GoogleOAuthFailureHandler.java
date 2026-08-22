package tw.com.ispan.gym.security.oauth;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.enums.account.OAuthRedirectReason;
import tw.com.ispan.gym.repository.account.OAuthTransactionRepository;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * Google 授權失敗（使用者取消、Google 端錯誤、state 驗證失敗等）的統一出口。
 * 對外一律導向固定白名單 reason，不把 exception message、原始 error code 或
 * state 放進 URL——理由與 {@link GoogleOAuthSuccessHandler} 相同。
 */
@Component
public class GoogleOAuthFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthFailureHandler.class);

    private final OAuthTransactionRepository transactions;
    private final OpaqueTokenGenerator tokenGenerator;
    private final GoogleOAuthProperties properties;
    private final Clock clock;

    public GoogleOAuthFailureHandler(OAuthTransactionRepository transactions, OpaqueTokenGenerator tokenGenerator,
            GoogleOAuthProperties properties, Clock clock) {
        this.transactions = transactions;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        try {
            OAuthRedirectReason reason = classify(request, exception);
            String correlationId = UUID.randomUUID().toString();

            String rawState = request.getParameter("state");
            if (rawState != null) {
                transactions.markFailedIfPending(tokenGenerator.hash(rawState), LocalDateTime.now(clock),
                        reason.name());
            }

            // 刻意只記內部分類、例外類別與 correlation ID：exception message 來自
            // Google 回傳的 error/error_description，屬於外部輸入，不該原樣進正式 log
            log.info("Google OAuth 授權失敗：reason={} exception={} correlationId={}",
                    reason, exception.getClass().getSimpleName(), correlationId);

            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            String url = UriComponentsBuilder.fromUriString(properties.frontendErrorUrl())
                    .queryParam("oauthError", reason.name().toLowerCase(Locale.ROOT))
                    .build().toUriString();
            response.sendRedirect(url);
        } finally {
            // HttpSession 只保存 OAuth 協定握手資料，不是系統登入態——見
            // GoogleOAuthSuccessHandler 的同一段說明，失敗路徑同樣要清乾淨
            clearOAuthSession(request);
        }
    }

    private void clearOAuthSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private OAuthRedirectReason classify(HttpServletRequest request, AuthenticationException exception) {
        if ("access_denied".equals(request.getParameter("error"))) {
            return OAuthRedirectReason.ACCESS_DENIED;
        }
        if (exception instanceof OAuth2AuthenticationException) {
            return OAuthRedirectReason.PROVIDER_ERROR;
        }
        return OAuthRedirectReason.UNEXPECTED_ERROR;
    }
}
