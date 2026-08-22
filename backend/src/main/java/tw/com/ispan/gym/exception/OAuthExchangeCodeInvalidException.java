package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** 交換碼不存在／已使用／已過期，一律同一個公開理由，不區分細節 */
public class OAuthExchangeCodeInvalidException extends ApiException {
    public OAuthExchangeCodeInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, "OAUTH_EXCHANGE_CODE_INVALID", message);
    }
}
