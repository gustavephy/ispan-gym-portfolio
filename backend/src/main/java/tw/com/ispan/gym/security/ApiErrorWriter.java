package tw.com.ispan.gym.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tw.com.ispan.gym.exception.ApiErrorResponse;

/**
 * 將 ApiErrorResponse 直接寫進 response。
 *
 * <p>filter 層（JWT 驗證、路徑授權）的錯誤發生在 DispatcherServlet 之前，
 * 不會經過 GlobalExceptionHandler，必須自行輸出 JSON 才能與 controller 層格式一致。
 */
@Component
public class ApiErrorWriter {

    /** 注入 Spring Boot 已配置好的 ObjectMapper，確保 LocalDateTime 與 controller 層序列化格式相同 */
    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 必須指定 charset，否則中文訊息在部分瀏覽器會亂碼
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(code, message, LocalDateTime.now()));
    }
}
