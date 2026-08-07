package tw.com.ispan.gym.cart.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(Integer productId) { super("??銝??? " + productId); }
}

