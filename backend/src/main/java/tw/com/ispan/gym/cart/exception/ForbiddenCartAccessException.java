package tw.com.ispan.gym.cart.exception;

public class ForbiddenCartAccessException extends RuntimeException {
    public ForbiddenCartAccessException(Long cartItemId) { super("無權存取此購物車項目：" + cartItemId); }
}

