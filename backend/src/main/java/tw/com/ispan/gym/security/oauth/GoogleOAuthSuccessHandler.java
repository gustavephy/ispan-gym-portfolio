package tw.com.ispan.gym.security.oauth;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

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
import jakarta.servlet.http.HttpSession;
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
    private final Clock clock;

    public GoogleOAuthSuccessHandler(OAuthTransactionRepository transactions, OpaqueTokenGenerator tokenGenerator,
            GoogleIdentityMapper identityMapper, GoogleOAuthLoginService loginService,
            GoogleOAuthLinkService linkService, GoogleOAuthProperties properties, Clock clock) {
        this.transactions = transactions;
        this.tokenGenerator = tokenGenerator;
        this.identityMapper = identityMapper;
        this.loginService = loginService;
        this.linkService = linkService;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        // HttpSession 只保存 OAuth 協定握手資料（AuthorizationRequestRepository／
        // OAuth2AuthorizedClient，可能含 Google access／ID token），不是系統登入態
        // （見類別註解）。不論流程走到哪個分支結束，都要在這裡清掉，不留到 session
        // 自然過期——finally 保證連上面 try/catch 都接不住的情況也一定會執行到。
        try {
            handle(request, response, authentication);
        } finally {
            clearOAuthSession(request);
        }
    }

    private void handle(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        String rawState = request.getParameter("state");
        OAuthTransactionView transaction = (rawState == null) ? null
                : transactions.findViewByStateHash(tokenGenerator.hash(rawState)).orElse(null);

        if (transaction == null) {
            redirectError(response, OAuthRedirectReason.FLOW_EXPIRED, null);
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
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

        // transaction 這裡已經是 CONSUMED——Google authorization code 也已經用掉了，
        // 兩者都不可能重來。以下任何非預期的 RuntimeException（Mapper/Login/Link Service
        // 內部的臭蟲、暫時性錯誤等，不是這幾個 Service 自己判定的業務拒絕）都必須在這裡
        // 兜住，不能讓它直接變成一般的 500 頁面：不記 token/state/exception message，
        // 只記 correlation id 與例外類別，導向固定的 unexpected_error 讓使用者重新發起
        // 整個 Google 登入——不會、也不能把這筆 transaction 改回 PENDING。
        try {
            OidcUser oidcUser = (OidcUser) ((OAuth2AuthenticationToken) authentication).getPrincipal();
            GoogleIdentity identity = identityMapper.map(oidcUser);

            if (transaction.purpose() == OAuthTransactionPurpose.LOGIN) {
                handleLogin(identity, transaction, response);
            } else {
                handleLink(identity, transaction, response);
            }
        } catch (RuntimeException ex) {
            String correlationId = UUID.randomUUID().toString();
            // 刻意不把 ex 本身傳給 logger——SLF4J 印例外物件時會連 exception message
            // 一起印出來，跟 GoogleOAuthFailureHandler 的 P3 修正是同一個理由：這裡的
            // message 可能是我們自己程式碼寫的、意外帶到 token／state／account 相關內容
            // 的字串，只留分類用的 class name 才能保證安全
            log.error("Google OAuth Success Handler 發生非預期例外：exception={} correlationId={}",
                    ex.getClass().getSimpleName(), correlationId);
            redirectReason(response, OAuthRedirectReason.UNEXPECTED_ERROR);
        }
    }

    private void clearOAuthSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
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
            transactions.markFailedIfPending(transaction.stateHash(), LocalDateTime.now(clock), reason.name());
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
