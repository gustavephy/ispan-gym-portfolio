package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** 新密碼等於目前密碼，或等於最近 4 組歷史密碼之一 */
public class PasswordReusedException extends ApiException {
    public PasswordReusedException(String message) {
        super(HttpStatus.CONFLICT, "PASSWORD_REUSED", message);
    }
}
