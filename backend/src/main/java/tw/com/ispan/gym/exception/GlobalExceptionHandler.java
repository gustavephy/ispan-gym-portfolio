package tw.com.ispan.gym.exception;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
	@ExceptionHandler(CustomException.class)
	public ResponseEntity<ApiErrorResponse> handleCustomException(CustomException ex) {
		log.warn("商業邏輯例外: {} {}", ex.getStatus(), ex.getMessage());
		ApiErrorResponse error = new ApiErrorResponse(ex.getStatus().value(), ex.getMessage(), LocalDateTime.now());
		return ResponseEntity.status(ex.getStatus()).body(error);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
		// 將所有欄位錯誤合併成一個訊息，用分號分隔
		String message = ex.getBindingResult().getFieldErrors().stream().map(FieldError::getDefaultMessage)
				.collect(Collectors.joining("; "));

		log.warn("欄位驗證失敗: {}", message);
		ApiErrorResponse error = new ApiErrorResponse(400, message, LocalDateTime.now());
		return ResponseEntity.badRequest().body(error);
	}

	@ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
	public ResponseEntity<ApiErrorResponse> handleAccessDeniedException(
			org.springframework.security.access.AccessDeniedException ex) {
		log.warn("存取被拒絕: {}", ex.getMessage());
		ApiErrorResponse error = new ApiErrorResponse(HttpStatus.FORBIDDEN.value(), "權限不足，拒絕存取", LocalDateTime.now());
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
	}

	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadableException(
			org.springframework.http.converter.HttpMessageNotReadableException ex) {
		log.warn("HTTP 訊息無法讀取 (Body 缺失或格式錯誤): {}", ex.getMessage());
		ApiErrorResponse error = new ApiErrorResponse(400, "請求主體缺失或格式錯誤，請確認後重試", LocalDateTime.now());
		return ResponseEntity.badRequest().body(error);
	}

	@ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiErrorResponse> handleMethodNotSupportedException(
			org.springframework.web.HttpRequestMethodNotSupportedException ex) {
		log.warn("不支援的 HTTP 方法: {}", ex.getMethod());
		ApiErrorResponse error = new ApiErrorResponse(HttpStatus.METHOD_NOT_ALLOWED.value(),
				"不支援的請求方法: " + ex.getMethod(), LocalDateTime.now());
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception ex) {
		log.error("未預期的伺服器錯誤", ex);
		ApiErrorResponse error = new ApiErrorResponse(500, "伺服器發生內部錯誤，請稍後再試", LocalDateTime.now());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
	}
}
