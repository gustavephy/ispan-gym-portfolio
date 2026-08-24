package tw.com.ispan.gym.exception;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 統一的錯誤回應格式。
 *
 * <p>errors 只在欄位驗證失敗時才有值（欄位名 → 該欄位的第一則訊息），
 * 其餘情況為 null 並由 {@code @JsonInclude(NON_NULL)} 整個略去，
 * 不讓既有的錯誤回應多出一個永遠是 null 的欄位。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(String code, String message, LocalDateTime timestamp,
        Map<String, String> errors) {

    public ApiErrorResponse(String code, String message, LocalDateTime timestamp) {
        this(code, message, timestamp, null);
    }
}
