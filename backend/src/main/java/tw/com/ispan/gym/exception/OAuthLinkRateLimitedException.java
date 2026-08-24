package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 同一 Account 短時間內發起太多次 LINK——跟密碼重新驗證的節流是不同的事：這裡是
 * 「已經證明知道密碼、身分沒問題」之後，防止重複建立 transaction 造成資源耗盡，
 * 不是猜密碼失敗，所以不是 401 而是 429。
 */
public class OAuthLinkRateLimitedException extends ApiException {
    public OAuthLinkRateLimitedException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "OAUTH_LINK_RATE_LIMITED", message);
    }
}
