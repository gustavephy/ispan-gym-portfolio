package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

public class CartItemTypeNotAllowedException extends ApiException {
    public CartItemTypeNotAllowedException(Integer productId) {
        super(HttpStatus.BAD_REQUEST, "CART_ITEM_TYPE_NOT_ALLOWED",
                "此商品類型不可加入購物車，購物車僅接受一般商品：productId=" + productId);
    }
}
