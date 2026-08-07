package tw.com.ispan.gym.cart.exception;

public class CartItemNotFoundException extends RuntimeException {
    public CartItemNotFoundException(Long cartItemId) { super("找不到購物車項目：" + cartItemId); }
}

