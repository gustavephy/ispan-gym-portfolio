package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 短時間內建立訂單的次數超過限制。建單端點已有 Idempotency-Key 擋「同一動作重送」，
 * 這裡擋的是「不同內容的訂單短時間狂建」，避免庫存被大量未付款訂單占住。
 */
public class OrderRateLimitedException extends ApiException {
    public OrderRateLimitedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "ORDER_RATE_LIMITED", "建立訂單太頻繁，請稍後再試");
    }
}
