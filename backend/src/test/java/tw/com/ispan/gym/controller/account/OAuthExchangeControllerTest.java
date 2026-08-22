package tw.com.ispan.gym.controller.account;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.account.OAuthExchangeResponse;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.OAuthExchangeCodeInvalidException;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthExchangeService;

/**
 * standalone MockMvc：路由、DTO 驗證、Service 委派與例外映射；不套 Spring Security——
 * 匿名可打是靠 SecurityConfig 的 {@code /api/auth/**} permitAll，那個規則本身由
 * EndpointAuthorizationTest 負責驗證。
 */
class OAuthExchangeControllerTest {

    private final GoogleOAuthExchangeService exchangeService = mock(GoogleOAuthExchangeService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new OAuthExchangeController(exchangeService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void exchange_成功時回傳200與JWT_並附安全Header() throws Exception {
        when(exchangeService.exchange("raw-exchange-code")).thenReturn(
                new OAuthExchangeResponse("real-jwt", 30, "user@example.com", List.of("MEMBER"), "/orders"));

        mockMvc.perform(post("/api/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "raw-exchange-code" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("real-jwt"))
                .andExpect(jsonPath("$.accountId").value(30))
                .andExpect(jsonPath("$.returnPath").value("/orders"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void exchange_Code為空白時400() throws Exception {
        mockMvc.perform(post("/api/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exchange_Service丟ExchangeCodeInvalid_回傳400與對應code() throws Exception {
        when(exchangeService.exchange(eq("bad-code")))
                .thenThrow(new OAuthExchangeCodeInvalidException("交換碼無效或已過期，請重新使用 Google 登入。"));

        mockMvc.perform(post("/api/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "bad-code" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_EXCHANGE_CODE_INVALID"));
    }
}
