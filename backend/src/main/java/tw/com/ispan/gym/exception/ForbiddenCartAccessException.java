package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenCartAccessException extends ApiException {
    public ForbiddenCartAccessException(Long cartItemId) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN_CART_ACCESS", "無權存取此購物車項目：" + cartItemId);
    }
}