package tw.com.ispan.gym.controller.account;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.account.AccountMeResponse;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.account.AccountService;

/**
 * standalone MockMvc：路由、Service 委派與例外映射；不套 Spring Security——匿名不能打
 * 這兩支端點是靠 SecurityConfig 的 anyRequest().authenticated()，由 EndpointAuthorizationTest
 * 負責驗證，這裡只測 accountId 一律來自 CurrentUserProvider，比照 PasswordControllerTest。
 */
class AccountControllerTest {

    private static final Integer ACCOUNT_ID = 30;

    private final AccountService accountService = mock(AccountService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AccountController(accountService, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void me_委派CurrentUser的AccountId並回傳身份快照() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(accountService.getMe(ACCOUNT_ID))
                .thenReturn(new AccountMeResponse(ACCOUNT_ID, "wendy@example.com", List.of("MEMBER"), createdAt));

        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.email").value("wendy@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"));
    }

    @Test
    void me_帳號不存在時映射404() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);
        when(accountService.getMe(ACCOUNT_ID)).thenThrow(new ResourceNotFoundException("帳號不存在"));

        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isNotFound());
    }

    @Test
    void logoutAllDevices_委派CurrentUser的AccountId並回傳204() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(ACCOUNT_ID);

        mockMvc.perform(post("/api/account/me/logout-all"))
                .andExpect(status().isNoContent());

        verify(accountService).logoutAllDevices(ACCOUNT_ID);
    }
}
