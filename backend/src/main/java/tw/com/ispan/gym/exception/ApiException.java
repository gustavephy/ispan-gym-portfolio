package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 所有可直接回應給前端的例外的父類。
 *
 * <p>把 HTTP 狀態碼與錯誤代碼帶在例外本身，GlobalExceptionHandler 只需一個
 * {@code @ExceptionHandler(ApiException.class)} 即可處理全部；新增例外時不必回頭改 handler。
 *
 * <p>使用方式與整體流程見 {@link tw.com.ispan.gym.exception 套件說明}。
 *
 * <p>訊息會原樣回傳給前端，因此子類的訊息不可包含 SQL、路徑等系統內部資訊。
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
