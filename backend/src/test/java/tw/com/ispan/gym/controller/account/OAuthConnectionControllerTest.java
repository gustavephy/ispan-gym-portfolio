package tw.com.ispan.gym.controller.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.account.OAuthConnectionsResponse;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.LastLoginMethodException;
import tw.com.ispan.gym.exception.OAuthAlreadyLinkedException;
import tw.com.ispan.gym.exception.ReauthenticationRequiredException;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthConnectionsService;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthLinkInitiationService;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthUnlinkService;

/**
 * standalone MockMvc：路由、DTO 驗證、Service 委派與例外映射；不套 Spring Security——
 * 匿名不能打這幾個端點是靠 SecurityConfig 的 anyRequest().authenticated()，那條規則本身
 * 由 EndpointAuthorizationTest 負責驗證，這裡只測 accountId 一律來自 CurrentUserProvider。
 */
class OAuthConnectionControllerTest {

    private static final Integer ACCOUNT_ID = 30;
    private static final String VALID_BODY = """
            { "currentPassword": "Current@2026" }
            """;

    private final GoogleOAuthConnectionsService connectionsService = mock(GoogleOAuthConnectionsService.class);
    private final GoogleOAuthLinkInitiationService linkInitiationService = mock(GoogleOAuthLinkInitiationService.class);
    private final GoogleOAuthUnlinkService unlinkService = mock(GoogleOAuthUnlinkService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new OAuthConnectionController(connectionsService, linkInitiationService, unlinkService,
                    currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void connections_委派CurrentUser的AccountId() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        when(connectionsService.query(ACCOUNT_ID)).thenReturn(OAuthConnectionsResponse.notLinked(true));

        mockMvc.perform(get("/api/account/me/oauth-connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.google.linked").value(false))
                .andExpect(jsonPath("$.hasPassword").value(true));
    }

    @Test
    void link_成功時回傳ContainsTransaction的AuthorizationUrl() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        when(linkInitiationService.initiate(eq(ACCOUNT_ID), eq("Current@2026"))).thenReturn("raw-link-state");

        mockMvc.perform(post("/api/account/me/oauth/google/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl")
                        .value("/oauth2/authorization/google?transaction=raw-link-state"));
    }

    @Test
    void link_CurrentPassword空白時400() throws Exception {
        mockMvc.perform(post("/api/account/me/oauth/google/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "currentPassword": "" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void link_密碼錯誤時回傳401() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        when(linkInitiationService.initiate(any(), any()))
                .thenThrow(new ReauthenticationRequiredException("目前密碼錯誤"));

        mockMvc.perform(post("/api/account/me/oauth/google/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    void link_已綁定時回傳409() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        when(linkInitiationService.initiate(any(), any()))
                .thenThrow(new OAuthAlreadyLinkedException("此帳號已經綁定一個 Google 帳號"));

        mockMvc.perform(post("/api/account/me/oauth/google/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OAUTH_ALREADY_LINKED"));
    }

    @Test
    void unlink_成功時回傳204() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);

        mockMvc.perform(delete("/api/account/me/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    void unlink_CurrentPassword空白時400() throws Exception {
        mockMvc.perform(delete("/api/account/me/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "currentPassword": "" }
                                """))
                .andExpect(status().isBadRequest());
    }

    /** 對應必補案例「唯一登入方式不能解除」 */
    @Test
    void unlink_唯一登入方式時回傳409() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        org.mockito.Mockito.doThrow(new LastLoginMethodException("此帳號沒有本機密碼"))
                .when(unlinkService).unlink(any(), any());

        mockMvc.perform(delete("/api/account/me/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_LOGIN_METHOD"));
    }

    @Test
    void unlink_密碼錯誤時回傳401() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        org.mockito.Mockito.doThrow(new ReauthenticationRequiredException("目前密碼錯誤"))
                .when(unlinkService).unlink(any(), any());

        mockMvc.perform(delete("/api/account/me/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }
}
