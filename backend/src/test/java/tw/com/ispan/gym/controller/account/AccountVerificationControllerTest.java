package tw.com.ispan.gym.controller.account;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.security.InMemoryRateLimiter;
import tw.com.ispan.gym.service.member.MemberRegisterService;

/**
 * AccountVerificationController 新增的 POST /api/accounts/check-email-availability
 * 測試：standalone MockMvc，MemberRegisterService 全 mock，InMemoryRateLimiter 用真的實例
 * （限流門檻只有 10/分鐘、30/小時，測試裡打十幾次就能驗證真實觸發，不需要 mock）。
 *
 * 既有的 verify-email／resend-verification-email 兩支端點沒有另外補測試——
 * 這批只新增這一支端點，其餘維持原本的覆蓋範圍不變。
 */
class AccountVerificationControllerTest {

    private final MemberRegisterService registerService = mock(MemberRegisterService.class);
    private final InMemoryRateLimiter rateLimiter = new InMemoryRateLimiter();

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AccountVerificationController(registerService, rateLimiter))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 可用時回available為true() throws Exception {
        when(registerService.isEmailAvailableForRegistration("new@example.com")).thenReturn(true);

        mockMvc.perform(post("/api/accounts/check-email-availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "new@example.com" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void 不可用時回available為false() throws Exception {
        when(registerService.isEmailAvailableForRegistration("taken@example.com")).thenReturn(false);

        mockMvc.perform(post("/api/accounts/check-email-availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "taken@example.com" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void 缺Email時回400且不呼叫Service() throws Exception {
        mockMvc.perform(post("/api/accounts/check-email-availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(registerService, never()).isEmailAvailableForRegistration(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void Email格式錯誤時回400() throws Exception {
        mockMvc.perform(post("/api/accounts/check-email-availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "not-an-email" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 委派時傳入Email() throws Exception {
        when(registerService.isEmailAvailableForRegistration("member@example.com")).thenReturn(true);

        mockMvc.perform(post("/api/accounts/check-email-availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "member@example.com" }
                                """))
                .andExpect(status().isOk());

        verify(registerService).isEmailAvailableForRegistration(eq("member@example.com"));
    }

    /** AccountVerificationController.CHECK_EMAIL_IP_MINUTE_MAX = 10；第 11 次同 IP 應觸發 429 */
    @Test
    void 同IP第11次觸發429限流() throws Exception {
        when(registerService.isEmailAvailableForRegistration(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/accounts/check-email-availability")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"email\": \"probe-" + i + "@example.com\" }"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/accounts/check-email-availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "probe-final@example.com" }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("EMAIL_CHECK_RATE_LIMITED"));
    }
}
