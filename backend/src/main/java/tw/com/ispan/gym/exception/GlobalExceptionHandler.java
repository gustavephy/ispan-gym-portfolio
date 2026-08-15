package tw.com.ispan.gym.exception;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> methodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "不支援的請求方法: " + ex.getMethod());
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

    /**
     * 資料庫層級的 constraint 違反（例如兩個並發請求用同一個全新 email 註冊，
     * 一個繞過程式碼的存在性檢查、直接撞上唯一索引）。不單獨列出會被下方的兜底接走，
     * 導致本該是使用者可理解的 409 變成看起來像系統壞掉的 500。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> dataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("資料庫限制衝突", ex);
        return response(HttpStatus.CONFLICT, "CONFLICT", "此資料已存在或違反資料庫限制，請確認後重試");
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
