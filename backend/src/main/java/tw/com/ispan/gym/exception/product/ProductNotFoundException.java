package tw.com.ispan.gym.exception.product;

import org.springframework.http.HttpStatus;
import tw.com.ispan.gym.exception.ApiException;

public class ProductNotFoundException extends ApiException {
    public ProductNotFoundException(Integer productId) {
        super(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "找不到商品：" + productId);
    }
}
