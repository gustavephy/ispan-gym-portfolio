package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** 註冊表單的 Email 即時可用性檢查被同一來源 IP 打太多次 */
public class EmailCheckRateLimitedException extends ApiException {
    public EmailCheckRateLimitedException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_CHECK_RATE_LIMITED", message);
    }
}
