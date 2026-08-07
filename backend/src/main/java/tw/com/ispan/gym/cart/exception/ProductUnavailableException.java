package tw.com.ispan.gym.cart.exception;

public class ProductUnavailableException extends RuntimeException {
    public ProductUnavailableException(Integer productId) { super("???桀??芯??? " + productId); }
}

