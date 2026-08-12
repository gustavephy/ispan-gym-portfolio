package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

public class DirectCheckoutNotAllowedException extends ApiException {
    public DirectCheckoutNotAllowedException(Integer productId) {
        super(HttpStatus.BAD_REQUEST, "DIRECT_CHECKOUT_NOT_ALLOWED",
                "此商品類型不可直接結帳，一般商品請透過購物車下單：productId=" + productId);
    }
}
