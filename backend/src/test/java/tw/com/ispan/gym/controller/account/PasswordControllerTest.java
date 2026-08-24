package tw.com.ispan.gym.controller.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.account.ChangePasswordRequest;
import tw.com.ispan.gym.dto.account.ChangePasswordResponse;
import tw.com.ispan.gym.exception.CurrentPasswordIncorrectException;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.PasswordChangeRateLimitedException;
import tw.com.ispan.gym.exception.PasswordNotConfiguredException;
import tw.com.ispan.gym.exception.PasswordReusedException;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.account.PasswordService;

/**
 * PasswordController 的路由、參數綁定、DTO 驗證、Service 委派與例外映射測試：
 * standalone MockMvc、PasswordService／CurrentUserProvider 全 mock，不套 Spring Security。
 *
 * <p>這支不驗權限——URL 層授權由 EndpointAuthorizationTest 負責（這支端點沒有
 * @PreAuthorize，純靠 SecurityConfig 的 anyRequest().authenticated()）。比照
 * AdminEmployeeControllerTest 的分工風格。
 */
class PasswordControllerTest {

    private static final Integer ACCOUNT_ID = 30;

    private final PasswordService passwordService = mock(PasswordService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PasswordController(passwordService, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private static final String VALID_BODY = """
            {
              "currentPassword": "Admin@12345",
              "newPassword": "Newpass@2026",
              "confirmPassword": "Newpass@2026"
            }
            """;

    // === 正常路徑：accountId 來自 CurrentUserProvider，不接受 body 帶入 =============

    @Test
    void changePassword_正確委派並帶入CurrentUserProvider解析出的accountId() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        ArgumentCaptor<ChangePasswordRequest> captor = ArgumentCaptor.forClass(ChangePasswordRequest.class);
        when(passwordService.changePassword(eq(ACCOUNT_ID), captor.capture()))
                .thenReturn(new ChangePasswordResponse("new-token", ACCOUNT_ID, "wendy-test@local.test",
                        List.of("MEMBER")));

        mockMvc.perform(post("/api/account/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-token"))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"));

        ChangePasswordRequest captured = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(captured.getCurrentPassword()).isEqualTo("Admin@12345");
        org.assertj.core.api.Assertions.assertThat(captured.getNewPassword()).isEqualTo("Newpass@2026");
    }

    /** body 不接受 accountId 欄位（DTO 上根本沒有這個欄位），越權修改別人密碼的路徑不存在 */
    @Test
    void changePassword_body即使帶accountId也被忽略_一律用CurrentUserProvider的值() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        when(passwordService.changePassword(eq(ACCOUNT_ID), any()))
                .thenReturn(new ChangePasswordResponse("t", ACCOUNT_ID, "e@x.com", List.of()));

        mockMvc.perform(post("/api/account/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 999,
                                  "currentPassword": "Admin@12345",
                                  "newPassword": "Newpass@2026",
                                  "confirmPassword": "Newpass@2026"
                                }
                                """))
                .andExpect(status().isOk());

        verify(passwordService).changePassword(eq(ACCOUNT_ID), any());
        verify(passwordService, never()).changePassword(eq(999), any());
    }

    // === DTO 驗證：三個必填欄位、@ValidPassword、confirm 一致性 =====================

    @Test
    void changePassword_缺currentPassword時回400且不呼叫Service() throws Exception {
        mockMvc.perform(post("/api/account/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "newPassword": "Newpass@2026", "confirmPassword": "Newpass@2026" }
                                """))
                .andExpect(status().isBadRequest());

        verify(passwordService, never()).changePassword(any(), any());
    }

    @Test
    void changePassword_新密碼不符合ValidPassword規則時回400() throws Exception {
        // 太短、沒有數字
        mockMvc.perform(post("/api/account/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "currentPassword": "Admin@12345", "newPassword": "short",
                                  "confirmPassword": "short" }
                                """))
                .andExpect(status().isBadRequest());

        verify(passwordService, never()).changePassword(any(), any());
    }

    @Test
    void changePassword_新密碼含空白時回400() throws Exception {
        mockMvc.perform(post("/api/account/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "currentPassword": "Admin@12345", "newPassword": "New pass@2026",
                                  "confirmPassword": "New pass@2026" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_新密碼與確認密碼不一致時回400且不呼叫Service() throws Exception {
        mockMvc.perform(post("/api/account/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "currentPassword": "Admin@12345", "newPassword": "Newpass@2026",
                                  "confirmPassword": "Different@2026" }
                                """))
                .andExpect(status().isBadRequest());

        verify(passwordService, never()).changePassword(any(), any());
    }

    // === 例外映射：Service 拋出的業務例外要對到正確狀態碼與 code ====================

    @Test
    void changePassword_OAuthOnly帳號映射409() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        when(passwordService.changePassword(eq(ACCOUNT_ID), any()))
                .thenThrow(new PasswordNotConfiguredException("此帳號尚未設定本機密碼，請使用原本的第三方登入方式"));

        mockMvc.perform(post("/api/account/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASSWORD_NOT_CONFIGURED"));
    }

    @Test
    void changePassword_目前密碼錯誤映射400() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        when(passwordService.changePassword(eq(ACCOUNT_ID), any()))
                .thenThrow(new CurrentPasswordIncorrectException("目前密碼錯誤"));

        mockMvc.perform(post("/api/account/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));
    }

    @Test
    void changePassword_密碼重用映射409() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        when(passwordService.changePassword(eq(ACCOUNT_ID), any()))
                .thenThrow(new PasswordReusedException("新密碼不得與近期使用過的密碼相同"));

        mockMvc.perform(post("/api/account/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASSWORD_REUSED"));
    }

    @Test
    void changePassword_限流觸發映射429() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        when(passwordService.changePassword(eq(ACCOUNT_ID), any()))
                .thenThrow(new PasswordChangeRateLimitedException("目前密碼錯誤次數過多，請稍後再試"));

        mockMvc.perform(post("/api/account/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_RATE_LIMITED"));
    }
}
