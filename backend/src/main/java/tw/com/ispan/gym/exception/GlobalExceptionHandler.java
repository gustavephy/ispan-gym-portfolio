package tw.com.ispan.gym.exception;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ApiErrorResponse> productNotFound(ProductNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(CartItemNotFoundException.class)
    ResponseEntity<ApiErrorResponse> cartItemNotFound(CartItemNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidQuantityException.class)
    ResponseEntity<ApiErrorResponse> invalidQuantity(InvalidQuantityException ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", ex.getMessage());
    }

    @ExceptionHandler(ProductUnavailableException.class)
    ResponseEntity<ApiErrorResponse> productUnavailable(ProductUnavailableException ex) {
        return response(HttpStatus.BAD_REQUEST, "PRODUCT_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<ApiErrorResponse> insufficientStock(InsufficientStockException ex) {
        return response(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", ex.getMessage());
    }

    @ExceptionHandler(ForbiddenCartAccessException.class)
    ResponseEntity<ApiErrorResponse> forbiddenCartAccess(ForbiddenCartAccessException ex) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN_CART_ACCESS", ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(ResourceNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<ApiErrorResponse> businessRule(BusinessRuleException ex) {
        return response(HttpStatus.CONFLICT, "BUSINESS_RULE_VIOLATION", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalidRequest(MethodArgumentNotValidException ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "請確認輸入資料是否正確");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> unauthenticated(AuthenticationException ex) {
        return response(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", ex.getMessage());
    }

    /**
     * 兜底：上面沒列舉到的例外都會走這裡，確保前端永遠收到相同的錯誤格式。
     * 例外細節只寫進 log，回應固定訊息以免洩漏系統內部資訊。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception ex) {
        log.error("未預期的錯誤", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "系統發生錯誤，請稍後再試");
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message, LocalDateTime.now()));
    }
}

