package tw.com.ispan.gym.security.oauth;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * 必須吃 {@link OidcUser}，不能只吃 Google UserInfo 端點回的原始 JSON——OidcUser
 * 代表 Spring Security 已經完成 issuer／audience／簽章／過期時間／nonce 的驗證，
 * 這裡只是把已驗證過的 claims 攤平成應用層物件。
 */
@Component
public class GoogleIdentityMapper {

    public GoogleIdentity map(OidcUser oidcUser) {
        Boolean emailVerified = oidcUser.getEmailVerified();
        return new GoogleIdentity(
                oidcUser.getSubject(),
                oidcUser.getEmail(),
                Boolean.TRUE.equals(emailVerified),
                oidcUser.getGivenName(),
                oidcUser.getFamilyName(),
                oidcUser.getPicture());
    }
}
