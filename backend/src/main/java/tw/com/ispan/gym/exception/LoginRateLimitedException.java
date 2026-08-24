package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 登入嘗試次數超過限制（同帳號 email 或同來源 IP 任一超標）。故意不區分是哪一種
 * 觸發、也不區分帳號是否存在，避免限流本身的觸發時機被拿來反推帳號是否存在。
 */
public class LoginRateLimitedException extends ApiException {
    public LoginRateLimitedException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", message);
    }
}
