package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

public class InvalidQuantityException extends ApiException {
    public InvalidQuantityException() {
        super(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "商品數量必須大於或等於 1");
    }
}