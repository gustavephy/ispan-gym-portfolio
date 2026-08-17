package tw.com.ispan.gym.exception.product;

import org.springframework.http.HttpStatus;
import tw.com.ispan.gym.exception.ApiException;

public class ProductUnavailableException extends ApiException {
    public ProductUnavailableException(Integer productId) {
        super(HttpStatus.BAD_REQUEST, "PRODUCT_UNAVAILABLE", "商品目前無法購買：" + productId);
    }
}