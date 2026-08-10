package tw.com.ispan.gym.common.exception;

import tw.com.ispan.gym.exception.CartItemNotFoundException;
import tw.com.ispan.gym.exception.ForbiddenCartAccessException;
import tw.com.ispan.gym.exception.ForbiddenSlotAccessException;
import tw.com.ispan.gym.exception.InsufficientStockException;
import tw.com.ispan.gym.exception.InvalidQuantityException;
import tw.com.ispan.gym.exception.ProductNotFoundException;
import tw.com.ispan.gym.exception.ProductUnavailableException;

import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
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

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message, LocalDateTime.now()));
    }

    @ExceptionHandler(ForbiddenSlotAccessException.class)
    ResponseEntity<ApiErrorResponse> forbiddenSlotAccess(ForbiddenSlotAccessException ex) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN_SLOT_ACCESS", ex.getMessage());
    }
}
