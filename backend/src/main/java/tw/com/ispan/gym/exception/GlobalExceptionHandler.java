package tw.com.ispan.gym.exception;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 專案自訂的例外一律走這裡，狀態碼與錯誤代碼由例外自己攜帶。
     * 新增 ApiException 子類時不需要再回頭修改本類別。
     */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> apiException(ApiException ex) {
        return response(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalidRequest(MethodArgumentNotValidException ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "請確認輸入資料是否正確");
    }

    /** request body 缺失或 JSON 格式錯誤，Jackson 反序列化失敗時丟出。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadableRequest(HttpMessageNotReadableException ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "請求主體缺失或格式錯誤，請確認後重試");
    }

    /** 缺少必要的查詢參數（如報表 /stats 沒帶 from、to）；不列出會被兜底接走誤報 500 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiErrorResponse> missingParameter(MissingServletRequestParameterException ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "缺少必要參數：" + ex.getParameterName());
    }

    /** 參數型別轉換失敗：日期格式錯誤、enum 值不存在、路徑 id 非數字等 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "參數格式不正確：" + ex.getName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> methodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "不支援的請求方法: " + ex.getMethod());
    }

    /** async 端點（如 AI 課表生成）超過 spring.mvc.async.request-timeout；不列出會被兜底誤報 500 */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException.class)
    ResponseEntity<ApiErrorResponse> asyncTimeout(org.springframework.web.context.request.async.AsyncRequestTimeoutException ex) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_TIMEOUT", "處理逾時，請稍後再試");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> unauthenticated(AuthenticationException ex) {
        return response(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", ex.getMessage());
    }

    /**
     * 方法層級授權失敗（@PreAuthorize 等）。若不單獨列出會被下方的兜底接走，
     * 導致本該是 403 的回應變成 500。
     * 注意：filter 層的授權失敗（如 SecurityConfig 的 /api/admin/**）不會經過這裡，
     * 需另外設定 AccessDeniedHandler 才能統一格式。
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> accessDenied(AccessDeniedException ex) {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "沒有權限執行此操作");
    }

    /**
     * 路徑打錯字、對應不到任何 API 時 Spring 會當成找不到的靜態資源丟出這個例外。
     * 若不單獨列出會被下方的兜底接走，導致本該是 404 的回應變成 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> noResourceFound(NoResourceFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到此 API 路徑");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> concurrentUpdateConflict(OptimisticLockingFailureException ex) {
        log.warn("樂觀鎖版本衝突: {}", ex.getMessage());
        return response(HttpStatus.CONFLICT, "CONCURRENT_UPDATE_CONFLICT", "資料已被其他人同時修改，請重新整理後再試一次");
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
