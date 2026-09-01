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
        // 安全網涵蓋整個方法本體，包括最前面的 transaction 查詢與原子消耗——那兩步
        // 一樣是真的 DB 呼叫，一樣可能因為暫時性錯誤丟出非預期的 RuntimeException，沒有
        // 理由只保護「取得 OIDC 身分」之後的部分（2026-08-22 code review 抓到這裡原本
        // 的安全網只從後半段開始，前半段的例外仍會直接變成一般 500 頁面，修正範圍擴大到
        // 整個方法）。不記 token/state/exception message，只記 correlation id 與例外類別，
        // 導向固定的 unexpected_error 讓使用者重新發起整個 Google 登入——已經消耗掉的
        // transaction 不會、也不能改回 PENDING。
        // transaction 宣告在 try 外：catch 需要它來決定「非預期例外」要導回哪裡——已經確認
        // 是 LINK 的話導帳號安全 callback，否則（LOGIN／purpose 還沒查到）保守導 /login。
        OAuthTransactionView transaction = null;
        try {
            String rawState = request.getParameter("state");
            transaction = (rawState == null) ? null
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
                // 併發下另一個 callback 先消耗了這筆 transaction。用 purpose 決定導向，但不再
                // markFailedIfPending（那筆列已不是 PENDING，且贏得消耗的那個 callback 會負責收尾）
                redirectPurposeAware(response, OAuthRedirectReason.FLOW_EXPIRED, transaction.purpose());
                return;
            }

            // transaction 這裡已經是 CONSUMED——Google authorization code 也已經用掉了，
            // 兩者都不可能重來。
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
            // 已經查到這次是 LINK（例外發生在 identityMapper／linkService 等後段）→ 導回帳號安全
            // callback，否則（purpose 未知）保守導 /login。不 markFailedIfPending：transaction
            // 已在上面被 consumeIfPending 消耗成 CONSUMED，或本來就查不到。
            redirectPurposeAware(response, OAuthRedirectReason.UNEXPECTED_ERROR,
                    transaction == null ? null : transaction.purpose());
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
            // LINK 業務拒絕（email 不符、身分已被佔用、併發下已綁定等）：導回「帳號安全頁」的
            // callback，不是共用的錯誤頁（見 redirectPurposeAware 的說明）。見第五批定案。
            case GoogleOAuthLinkService.Rejected rejected ->
                redirectPurposeAware(response, rejected.reason(), OAuthTransactionPurpose.LINK);
        }
    }

    private void redirectError(HttpServletResponse response, OAuthRedirectReason reason, OAuthTransactionView transaction)
            throws IOException {
        if (transaction != null) {
            transactions.markFailedIfPending(transaction.stateHash(), LocalDateTime.now(clock), reason.name());
        }
        redirectPurposeAware(response, reason, transaction == null ? null : transaction.purpose());
    }

    /**
     * 依 transaction 的 purpose 選導向：LINK → 帳號安全頁 callback（frontendLinkResultUrl），
     * 其餘（LOGIN、purpose 未知）→ 共用錯誤頁（frontendErrorUrl，目前是 /login）。
     * frontendErrorUrl 對已登入者不友善（router 的 beforeEnter 會把已登入者導離 /login），
     * 所以 LINK 流程的失敗訊息一定要走 frontendLinkResultUrl 才看得到。
     */
    private void redirectPurposeAware(HttpServletResponse response, OAuthRedirectReason reason,
            OAuthTransactionPurpose purpose) throws IOException {
        String baseUrl = purpose == OAuthTransactionPurpose.LINK
                ? properties.frontendLinkResultUrl()
                : properties.frontendErrorUrl();
        log.info("Google OAuth 流程結束於非成功狀態：{}", reason);
        redirect(response, baseUrl, "oauthError", reason.name().toLowerCase(Locale.ROOT));
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
