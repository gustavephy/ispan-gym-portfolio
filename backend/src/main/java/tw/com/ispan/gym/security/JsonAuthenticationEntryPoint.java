package tw.com.ispan.gym.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 未登入或 token 無效時回傳與 controller 層相同格式的 401。
 *
 * <p>取代原本的 HttpStatusEntryPoint（只回狀態碼、body 為空），讓前端能一律以
 * ApiErrorResponse 的 code 欄位判斷錯誤。
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter errorWriter;

    public JsonAuthenticationEntryPoint(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        // 不揭露是「未帶 token」還是「token 過期／偽造」，避免協助攻擊者偵察
        errorWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "請先登入後再執行此操作");
    }
}
