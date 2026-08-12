package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

public class InsufficientStockException extends ApiException {
    public InsufficientStockException(Integer productId, long requested, long available) {
        super(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                "商品庫存不足：productId=" + productId + ", requested=" + requested + ", available=" + available);
    }
}