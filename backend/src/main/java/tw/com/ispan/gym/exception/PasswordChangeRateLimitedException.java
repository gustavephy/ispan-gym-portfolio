package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** 同帳號 15 分鐘內目前密碼連續猜錯達上限；已登入端點，明講不算洩漏帳號存在與否 */
public class PasswordChangeRateLimitedException extends ApiException {
    public PasswordChangeRateLimitedException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "PASSWORD_CHANGE_RATE_LIMITED", message);
    }
}
