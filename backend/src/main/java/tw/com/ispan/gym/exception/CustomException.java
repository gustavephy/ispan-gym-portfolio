package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 自定義商業邏輯例外
 *
 * 為什麼需要自定義例外？
 *   Spring Boot 預設的例外訊息對前端不友好，
 *   我們需要統一回傳 { code: 409, message: "..." } 格式。
 *
 * 使用方式：
 *   在 Service 中判斷商業規則失敗時，拋出此例外：
 *   throw new CustomException(HttpStatus.CONFLICT, "此時段已被預約");
 *
 * 常用 HttpStatus：
 *   400 BAD_REQUEST       → 請求參數錯誤
 *   401 UNAUTHORIZED      → 未登入
 *   403 FORBIDDEN         → 無權限
 *   404 NOT_FOUND         → 資源不存在
 *   409 CONFLICT          → 衝突（如：時段已滿、庫存不足）
 */
public class CustomException extends RuntimeException {

    private final HttpStatus status;

    public CustomException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}