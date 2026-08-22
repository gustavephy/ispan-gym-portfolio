package tw.com.ispan.gym.security.oauth;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.enums.account.OAuthRedirectReason;
import tw.com.ispan.gym.enums.account.OAuthTransactionPurpose;
import tw.com.ispan.gym.enums.account.OAuthTransactionStatus;
import tw.com.ispan.gym.repository.account.OAuthTransactionRepository;
import tw.com.ispan.gym.repository.account.OAuthTransactionView;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthLinkService;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthLoginService;

/**
 * Google Callback 成功後的分流器。這裡執行時 Spring Security 已經完成 authorization
 * code 交換、state 協定驗證、OIDC issuer/簽章/audience/nonce/過期驗證——這裡只做
 * 業務層判定：依 server-side {@link OAuthTransactionView} 分流，絕不信任 URL 上的任何
 * query 參數來決定用途或目標帳號。
 */
@Component
public class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthSuccessHandler.class);

    private final OAuthTransactionRepository transactions;
    private final OpaqueTokenGenerator tokenGenerator;
    private final GoogleIdentityMapper identityMapper;
    private final GoogleOAuthLoginService loginService;
    private final GoogleOAuthLinkService linkService;
    private final GoogleOAuthProperties properties;

    public GoogleOAuthSuccessHandler(OAuthTransactionRepository transactions, OpaqueTokenGenerator tokenGenerator,
            GoogleIdentityMapper identityMapper, GoogleOAuthLoginService loginService,
            GoogleOAuthLinkService linkService, GoogleOAuthProperties properties) {
        this.transactions = transactions;
        this.tokenGenerator = tokenGenerator;
        this.identityMapper = identityMapper;
        this.loginService = loginService;
        this.linkService = linkService;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        String rawState = request.getParameter("state");
        OAuthTransactionView transaction = (rawState == null) ? null
                : transactions.findViewByStateHash(tokenGenerator.hash(rawState)).orElse(null);

        if (transaction == null) {
            redirectError(response, OAuthRedirectReason.FLOW_EXPIRED, null);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (transaction.status() != OAuthTransactionStatus.PENDING || !transaction.expiresAt().isAfter(now)) {
            OAuthRedirectReason reason = transaction.status() == OAuthTransactionStatus.SUPERSEDED
                    ? OAuthRedirectReason.FLOW_REPLACED
                    : OAuthRedirectReason.FLOW_EXPIRED;
            redirectError(response, reason, transaction);
            return;
        }

        // 原子消耗：只有真的贏得這次處理權才能繼續，避免併發 callback 重複處理同一 transaction
        int consumed = transactions.consumeIfPending(transaction.stateHash(), now);
        if (consumed == 0) {
            redirectError(response, OAuthRedirectReason.FLOW_EXPIRED, null);
            return;
        }

        OidcUser oidcUser = (OidcUser) ((OAuth2AuthenticationToken) authentication).getPrincipal();
        GoogleIdentity identity = identityMapper.map(oidcUser);

        if (transaction.purpose() == OAuthTransactionPurpose.LOGIN) {
            handleLogin(identity, transaction, response);
        } else {
            handleLink(identity, transaction, response);
        }
    }

    private void handleLogin(GoogleIdentity identity, OAuthTransactionView transaction, HttpServletResponse response)
            throws IOException {
        GoogleOAuthLoginService.Outcome outcome = loginService.login(identity, transaction.returnPath());
        switch (outcome) {
            case GoogleOAuthLoginService.Authenticated authenticated ->
                redirect(response, properties.frontendSuccessUrl(), "code", authenticated.rawExchangeCode());
            case GoogleOAuthLoginService.RegistrationRequired registrationRequired ->
                redirect(response, properties.frontendRegistrationUrl(), "token",
                        registrationRequired.rawRegistrationToken());
            case GoogleOAuthLoginService.Rejected rejected -> redirectReason(response, rejected.reason());
        }
    }

    private void handleLink(GoogleIdentity identity, OAuthTransactionView transaction, HttpServletResponse response)
            throws IOException {
        GoogleOAuthLinkService.Outcome outcome = linkService.link(transaction.accountId(), identity);
        switch (outcome) {
            case GoogleOAuthLinkService.Linked ignored ->
                redirect(response, properties.frontendLinkResultUrl(), "result", "linked");
            case GoogleOAuthLinkService.Rejected rejected -> redirectReason(response, rejected.reason());
        }
    }

    private void redirectError(HttpServletResponse response, OAuthRedirectReason reason, OAuthTransactionView transaction)
            throws IOException {
        if (transaction != null) {
            transactions.markFailedIfPending(transaction.stateHash(), LocalDateTime.now(), reason.name());
        }
        redirectReason(response, reason);
    }

    private void redirectReason(HttpServletResponse response, OAuthRedirectReason reason) throws IOException {
        log.info("Google OAuth 流程結束於非成功狀態：{}", reason);
        redirect(response, properties.frontendErrorUrl(), "oauthError", reason.name().toLowerCase(Locale.ROOT));
    }

    private void redirect(HttpServletResponse response, String baseUrl, String param, String value)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        String url = UriComponentsBuilder.fromUriString(baseUrl).queryParam(param, value).build().toUriString();
        response.sendRedirect(url);
    }
}
