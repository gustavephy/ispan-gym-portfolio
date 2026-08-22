package tw.com.ispan.gym.security.oauth;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.entity.account.OAuthTransaction;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthTransactionPurpose;
import tw.com.ispan.gym.enums.account.OAuthTransactionStatus;
import tw.com.ispan.gym.repository.account.OAuthTransactionRepository;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 把 OAuth {@code state} 換成不透明高熵值，語意全部存在 server-side
 * {@link OAuthTransaction}——見 memory/google_oauth_phase_scope.md 的 state 模型。
 *
 * <p>LOGIN：{@code /oauth2/authorization/google}，這裡直接建立新 transaction。
 * <p>LINK：{@code /oauth2/authorization/google?transaction=<opaque>}，這個
 * {@code transaction} 值本身是一個 bearer capability token——此端點是
 * {@code permitAll}，安全性建立在該值高熵不可預測、單次消耗、且發起時已固定
 * 綁定 purpose=LINK 與 account id，而不是請求當下的身分驗證。只在這裡做唯讀驗證
 * （PENDING／未過期／provider／purpose 相符），驗證失敗直接回 null 中止，
 * 不建立 authorization request、不打 Google；真正消耗發生在 Callback。
 *
 * <p>{@code app.oauth.google.enabled=false} 時，兩個 {@code resolve()} 一律回傳
 * {@code null}，在建立任何 {@link OAuthTransaction} 或呼叫 delegate 之前就中止——
 * 停用不只是前端隱藏按鈕的裝飾，這裡是後端真正拒絕開始流程的地方，不依賴「反正
 * Google 會因為 client id/secret 是 placeholder 而拒絕」這種間接效果。
 */
@Component
public class GoogleOAuthAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String AUTHORIZATION_BASE_URI = "/oauth2/authorization";
    private static final String LINK_TRANSACTION_PARAM = "transaction";
    private static final String RETURN_PATH_PARAM = "returnPath";

    private final OAuth2AuthorizationRequestResolver delegate;
    private final OAuthTransactionRepository transactions;
    private final OpaqueTokenGenerator tokenGenerator;
    private final GoogleOAuthProperties properties;
    private final Clock clock;

    public GoogleOAuthAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrations,
            OAuthTransactionRepository transactions, OpaqueTokenGenerator tokenGenerator,
            GoogleOAuthProperties properties, Clock clock) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrations, AUTHORIZATION_BASE_URI);
        this.transactions = transactions;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return null;
        }
        return customize(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        if (!properties.isEnabled()) {
            return null;
        }
        return customize(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request) {
        if (authorizationRequest == null) {
            return null;
        }
        String linkTransaction = request.getParameter(LINK_TRANSACTION_PARAM);
        String state = (linkTransaction != null)
                ? resolveLinkState(linkTransaction)
                : createLoginTransaction(request.getParameter(RETURN_PATH_PARAM));
        if (state == null) {
            return null;
        }
        return OAuth2AuthorizationRequest.from(authorizationRequest).state(state).build();
    }

    private String resolveLinkState(String rawTransaction) {
        String hash = tokenGenerator.hash(rawTransaction);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean valid = transactions.findViewByStateHash(hash)
                .filter(t -> t.status() == OAuthTransactionStatus.PENDING)
                .filter(t -> t.expiresAt().isAfter(now))
                .filter(t -> t.purpose() == OAuthTransactionPurpose.LINK)
                .filter(t -> t.oauthProvider() == OAuthProvider.GOOGLE)
                .isPresent();
        return valid ? rawTransaction : null;
    }

    private String createLoginTransaction(String rawReturnPath) {
        String rawState = tokenGenerator.generate();
        OAuthTransaction transaction = new OAuthTransaction();
        transaction.setStateHash(tokenGenerator.hash(rawState));
        transaction.setOauthProvider(OAuthProvider.GOOGLE);
        transaction.setPurpose(OAuthTransactionPurpose.LOGIN);
        transaction.setReturnPath(ReturnPathValidator.sanitize(rawReturnPath).orElse(null));
        transaction.setExpiresAt(LocalDateTime.now(clock).plus(properties.transactionTtl()));
        transactions.save(transaction);
        return rawState;
    }
}
