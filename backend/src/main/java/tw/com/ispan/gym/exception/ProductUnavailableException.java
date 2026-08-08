package tw.com.ispan.gym.exception;

public class ProductUnavailableException extends RuntimeException {
    public ProductUnavailableException(Integer productId) { super("商品目前無法購買：" + productId); }
}

