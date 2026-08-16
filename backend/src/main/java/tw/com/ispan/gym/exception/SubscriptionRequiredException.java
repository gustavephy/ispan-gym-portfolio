package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 購買私教課程前，會員名下必須有一筆未到期的有效訂閱；查無時丟出，統一以 403 回應。
 */
public class SubscriptionRequiredException extends ApiException {
    public SubscriptionRequiredException() {
        super(HttpStatus.FORBIDDEN, "SUBSCRIPTION_REQUIRED", "需先訂閱會員方案才能購買私教課程");
    }
}
