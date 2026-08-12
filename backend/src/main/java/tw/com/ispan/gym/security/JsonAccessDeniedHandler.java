package tw.com.ispan.gym.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 已登入但權限不足時回傳與 controller 層相同格式的 403。
 *
 * <p>Spring Security 預設會 forward 到 /error，產生的 JSON 欄位與 ApiErrorResponse 不同；
 * 這裡直接輸出，與 GlobalExceptionHandler 的 ACCESS_DENIED 保持一致。
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter errorWriter;

    public JsonAccessDeniedHandler(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        errorWriter.write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "沒有權限執行此操作");
    }
}
