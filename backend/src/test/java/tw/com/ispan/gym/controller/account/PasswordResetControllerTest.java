package tw.com.ispan.gym.controller.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.PasswordReusedException;
import tw.com.ispan.gym.exception.ResetTokenInvalidException;
import tw.com.ispan.gym.service.account.PasswordService;

/**
 * PasswordResetController（忘記密碼申請／憑 Token 重設密碼）的路由、驗證、委派與
 * 例外映射測試：standalone MockMvc、PasswordService 全 mock，不套 Spring Security。
 * 兩支端點都是匿名，URL 層授權（/api/auth/** permitAll）由 EndpointAuthorizationTest 負責。
 */
class PasswordResetControllerTest {

    private final PasswordService passwordService = mock(PasswordService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PasswordResetController(passwordService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    // === POST /api/auth/forgot-password ==========================================

    @Test
    void forgotPassword_有效Email回202固定訊息並委派帶入來源IP() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "wendy-test@local.test" }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("若此 Email 可使用密碼登入，我們將寄送密碼重設信。"));

        verify(passwordService).requestPasswordReset(eq("wendy-test@local.test"), any());
    }

    @Test
    void forgotPassword_缺Email時回400且不呼叫Service() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(passwordService, never()).requestPasswordReset(any(), any());
    }

    @Test
    void forgotPassword_Email格式錯誤時回400且不呼叫Service() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "not-an-email" }
                                """))
                .andExpect(status().isBadRequest());

        verify(passwordService, never()).requestPasswordReset(any(), any());
    }

    /**
     * 防列舉的核心斷言之一：不論 Email 是否存在／是否為 OAuth-only／是否被限流，
     * Service 內部一律靜默處理（見 PasswordServiceTest），Controller 這層完全不會
     * 因為那些狀態而改變回應——這裡只驗證 Controller 不會意外把 Service 內部狀態
     * 洩漏出來（Service 回傳型別是 void，沒有東西可以洩漏，這正是防列舉的設計重點）。
     */
    @Test
    void forgotPassword_不論Service內部做了什麼回應格式都固定() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "definitely-not-registered@example.com" }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("若此 Email 可使用密碼登入，我們將寄送密碼重設信。"));
    }

    // === POST /api/auth/reset-password ===========================================

    private static final String VALID_RESET_BODY = """
            {
              "token": "raw-token-value",
              "newPassword": "Newpass@2026",
              "confirmPassword": "Newpass@2026"
            }
            """;

    @Test
    void resetPassword_有效body回204並委派() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESET_BODY))
                .andExpect(status().isNoContent());

        verify(passwordService).resetPassword("raw-token-value", "Newpass@2026");
    }

    @Test
    void resetPassword_缺token時回400且不呼叫Service() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "newPassword": "Newpass@2026", "confirmPassword": "Newpass@2026" }
                                """))
                .andExpect(status().isBadRequest());

        verify(passwordService, never()).resetPassword(any(), any());
    }

    @Test
    void resetPassword_新密碼與確認密碼不一致時回400() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "token": "t", "newPassword": "Newpass@2026",
                                  "confirmPassword": "Different@2026" }
                                """))
                .andExpect(status().isBadRequest());

        verify(passwordService, never()).resetPassword(any(), any());
    }

    /** 失敗時仍要走各自的業務錯誤映射，不能被 @ResponseStatus(NO_CONTENT) 蓋掉 */
    @Test
    void resetPassword_ServiceThrowResetTokenInvalid時映射400_不是204() throws Exception {
        doThrow(new ResetTokenInvalidException("重設連結無效或已過期，請重新申請。"))
                .when(passwordService).resetPassword(any(), any());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESET_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
    }

    @Test
    void resetPassword_ServiceThrowPasswordReused時映射409() throws Exception {
        doThrow(new PasswordReusedException("新密碼不得與近期使用過的密碼相同"))
                .when(passwordService).resetPassword(any(), any());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESET_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASSWORD_REUSED"));
    }
}
