package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** Email 已有既有 Account，但尚未綁定這個 Google 身分——不得透露既有帳號的角色或狀態 */
public class OAuthAccountLinkRequiredException extends ApiException {
    public OAuthAccountLinkRequiredException(String message) {
        super(HttpStatus.CONFLICT, "OAUTH_ACCOUNT_LINK_REQUIRED", message);
    }
}
