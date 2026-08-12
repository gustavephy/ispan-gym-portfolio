package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

public class ProductNotFoundException extends ApiException {
    public ProductNotFoundException(Integer productId) {
        super(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "找不到商品：" + productId);
    }
}
