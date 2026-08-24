package tw.com.ispan.gym.controller.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.OAuthAccountLinkRequiredException;
import tw.com.ispan.gym.exception.OAuthRegistrationTicketInvalidException;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthRegistrationService;

/**
 * standalone MockMvc：路由、DTO 驗證（尤其台灣手機格式）、Service 委派與例外映射。
 * email／Google subject 不接受 request body 傳入這件事，是 DTO 本身沒有這兩個欄位
 * 保證的，不需要另外測「傳了也沒用」。
 */
class OAuthRegistrationControllerTest {

    private static final String VALID_BODY = """
            {
              "registrationToken": "raw-registration-token",
              "firstName": "小明",
              "lastName": "王",
              "phone": "0912345678"
            }
            """;

    private final GoogleOAuthRegistrationService registrationService = mock(GoogleOAuthRegistrationService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new OAuthRegistrationController(registrationService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void complete_成功時回傳200與一次性交換碼_並附安全Header() throws Exception {
        when(registrationService.complete(eq("raw-registration-token"), any())).thenReturn("raw-exchange-code");

        mockMvc.perform(post("/api/auth/oauth/google/registration/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("raw-exchange-code"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void complete_RegistrationToken空白時400() throws Exception {
        mockMvc.perform(post("/api/auth/oauth/google/registration/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "registrationToken": "", "firstName": "小明", "lastName": "王", "phone": "0912345678" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void complete_姓名空白時400() throws Exception {
        mockMvc.perform(post("/api/auth/oauth/google/registration/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "registrationToken": "raw-registration-token", "firstName": "", "lastName": "王", "phone": "0912345678" }
                                """))
                .andExpect(status().isBadRequest());
    }

    /** 對應必補案例「Google 沒有 phone 時不建立殘缺 Member」——手機格式必須是台灣手機號碼 */
    @Test
    void complete_手機格式不符台灣手機規則時400() throws Exception {
        mockMvc.perform(post("/api/auth/oauth/google/registration/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "registrationToken": "raw-registration-token", "firstName": "小明", "lastName": "王", "phone": "12345" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void complete_手機為空白時400() throws Exception {
        mockMvc.perform(post("/api/auth/oauth/google/registration/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "registrationToken": "raw-registration-token", "firstName": "小明", "lastName": "王", "phone": "" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void complete_Ticket無效時回傳400() throws Exception {
        when(registrationService.complete(any(), any()))
                .thenThrow(new OAuthRegistrationTicketInvalidException("補資料連結無效或已過期，請重新使用 Google 登入。"));

        mockMvc.perform(post("/api/auth/oauth/google/registration/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_REGISTRATION_TICKET_INVALID"));
    }

    /** 對應必補案例「補資料期間 Email 被其他流程註冊時不自動綁定」——併發衝突轉為 409 */
    @Test
    void complete_併發衝突EmailNowExists_回傳409() throws Exception {
        when(registrationService.complete(any(), any()))
                .thenThrow(new OAuthAccountLinkRequiredException("此 Email 已有帳號，請先使用原登入方式確認身分，再綁定 Google。"));

        mockMvc.perform(post("/api/auth/oauth/google/registration/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OAUTH_ACCOUNT_LINK_REQUIRED"));
    }
}
