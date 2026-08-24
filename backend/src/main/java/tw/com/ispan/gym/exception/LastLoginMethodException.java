package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** OAuth-only Account（沒有本機密碼）不能解除唯一的登入方式 */
public class LastLoginMethodException extends ApiException {
    public LastLoginMethodException(String message) {
        super(HttpStatus.CONFLICT, "LAST_LOGIN_METHOD", message);
    }
}
