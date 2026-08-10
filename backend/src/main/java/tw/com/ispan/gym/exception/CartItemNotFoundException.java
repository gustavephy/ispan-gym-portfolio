package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

public class CartItemNotFoundException extends ApiException {
    public CartItemNotFoundException(Long cartItemId) {
        super(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND", "找不到購物車項目：" + cartItemId);
    }
}
