package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** LINK 時 Google email 必須與 Account email 一致（v1 保守規則，見 docs/google-oauth-design-notes.md） */
public class OAuthLinkEmailMismatchException extends ApiException {
    public OAuthLinkEmailMismatchException(String message) {
        super(HttpStatus.CONFLICT, "OAUTH_LINK_EMAIL_MISMATCH", message);
    }
}
