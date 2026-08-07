package tw.com.ispan.gym.cart.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(Integer productId) { super("找不到商品：" + productId); }
}

