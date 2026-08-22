package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** 這個 Google 身分已綁定另一個 Account——不得回傳對方 Account 的任何資訊 */
public class OAuthIdentityInUseException extends ApiException {
    public OAuthIdentityInUseException(String message) {
        super(HttpStatus.CONFLICT, "OAUTH_IDENTITY_IN_USE", message);
    }
}
