package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** 目前登入的 Account 已經綁定一個 Google 帳號，一個 Account 只能綁一個 */
public class OAuthAlreadyLinkedException extends ApiException {
    public OAuthAlreadyLinkedException(String message) {
        super(HttpStatus.CONFLICT, "OAUTH_ALREADY_LINKED", message);
    }
}
