package tw.com.ispan.gym.cart.exception;

public class CartItemNotFoundException extends RuntimeException {
    public CartItemNotFoundException(Long cartItemId) { super("鞈潛頠??桐?摮: " + cartItemId); }
}

