package tw.com.ispan.gym.controller.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jayway.jsonpath.JsonPath;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.security.ApiErrorWriter;
import tw.com.ispan.gym.security.InMemoryRateLimiter;
import tw.com.ispan.gym.security.JsonAccessDeniedHandler;
import tw.com.ispan.gym.security.JsonAuthenticationEntryPoint;
import tw.com.ispan.gym.security.JwtAuthFilter;
import tw.com.ispan.gym.security.JwtUtil;

/**
 * /api/auth/login 的煙霧測試，沿用 JwtSecuritySmokeTest 的做法：
 * 不連資料庫、repository 全 mock，但走真實的 SecurityConfig filter chain、
 * BCryptPasswordEncoder、JwtUtil 與 GlobalExceptionHandler，
 * 驗證「登入拿 token → 用 token 打受保護端點」的完整迴圈。
 */
@SpringBootTest(
        classes = {AuthControllerLoginTest.TestApp.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // application.properties 硬寫 spring.profiles.active=dev，會讓 @Profile("!dev") 的
        // SecurityConfig 不生效、落入 Spring Security 預設鏈（CSRF 全擋 POST），必須覆寫掉
        "spring.profiles.active=test",
        "app.jwt.secret=dGVzdC1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTYtc2lnbmluZyEh",
        "app.jwt.issuer=ispan-gym",
        "app.jwt.access-token-ttl=PT2H",
        "app.oauth.google.enabled=false",
        "app.oauth.frontend-success-url=http://localhost:5173/oauth/callback",
        "app.oauth.frontend-registration-url=http://localhost:5173/oauth/register",
        "app.oauth.frontend-link-result-url=http://localhost:5173/account/oauth/callback",
        "app.oauth.frontend-error-url=http://localhost:5173/login",
        "app.oauth.transaction-ttl-minutes=10",
        "app.oauth.registration-ticket-ttl-minutes=15",
        "app.oauth.exchange-code-ttl-seconds=90"
})
class AuthControllerLoginTest {

    private static final String RAW_PASSWORD = "P@ssw0rd!";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    /** BCrypt 雜湊很慢，全類別共用一份，別在每個測試重算 */
    private static final String PASSWORD_HASH = ENCODER.encode(RAW_PASSWORD);

    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(
            exclude = {
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class
            })
    @org.springframework.context.annotation.Configuration
    @Import({
        TestBeans.class,
        ProbeController.class,
        AuthController.class,
        GlobalExceptionHandler.class,
        tw.com.ispan.gym.config.PasswordEncoderConfig.class,
        JwtUtil.class,
        JwtAuthFilter.class,
        tw.com.ispan.gym.security.AccountAuthorityResolver.class,
        tw.com.ispan.gym.security.AccountLoginPolicy.class,
        ApiErrorWriter.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        tw.com.ispan.gym.config.SecurityConfig.class,
        // Google OAuth 協定鏈的必要 bean——SecurityConfig 現在同時建立 oauth 鏈，
        // 少了任何一個都會讓 context 組不起來（見 SecurityConfig 建構子）
        tw.com.ispan.gym.config.GoogleOAuthProperties.class,
        tw.com.ispan.gym.config.ClockConfig.class,
        tw.com.ispan.gym.security.InMemoryRateLimiter.class,
        tw.com.ispan.gym.security.OpaqueTokenGenerator.class,
        tw.com.ispan.gym.security.oauth.GoogleIdentityMapper.class,
        tw.com.ispan.gym.service.account.oauth.GoogleOAuthLoginService.class,
        tw.com.ispan.gym.service.account.oauth.GoogleOAuthLinkService.class,
        tw.com.ispan.gym.security.oauth.GoogleOAuthAuthorizationRequestResolver.class,
        tw.com.ispan.gym.security.oauth.GoogleOAuthSuccessHandler.class,
        tw.com.ispan.gym.security.oauth.GoogleOAuthFailureHandler.class
    })
    static class TestApp {}

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        AccountRepository accountRepository() {
            return Mockito.mock(AccountRepository.class);
        }

        @Bean
        @Primary
        MemberRepository memberRepository() {
            return Mockito.mock(MemberRepository.class);
        }

        @Bean
        @Primary
        EmployeeRepository employeeRepository() {
            return Mockito.mock(EmployeeRepository.class);
        }

        @Bean
        @Primary
        tw.com.ispan.gym.repository.account.OauthAccountRepository oauthAccountRepository() {
            return Mockito.mock(tw.com.ispan.gym.repository.account.OauthAccountRepository.class);
        }

        @Bean
        @Primary
        tw.com.ispan.gym.repository.trainer.TrainerRepository trainerRepository() {
            return Mockito.mock(tw.com.ispan.gym.repository.trainer.TrainerRepository.class);
        }

        @Bean
        @Primary
        tw.com.ispan.gym.repository.account.OAuthTransactionRepository oauthTransactionRepository() {
            return Mockito.mock(tw.com.ispan.gym.repository.account.OAuthTransactionRepository.class);
        }

        @Bean
        @Primary
        tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository oauthRegistrationTicketRepository() {
            return Mockito.mock(tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository.class);
        }

        @Bean
        @Primary
        tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository oauthExchangeCodeRepository() {
            return Mockito.mock(tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository.class);
        }
    }

    /** 受保護端點探針，驗證登入拿到的 token 真的能用 */
    @RestController
    static class ProbeController {
        @GetMapping("/api/orders")
        String secured() {
            return "ok";
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired AccountRepository accounts;
    @Autowired MemberRepository members;
    @Autowired EmployeeRepository employees;
    @Autowired InMemoryRateLimiter rateLimiter;

    /**
     * {@link InMemoryRateLimiter} 是這個測試類別共用 Spring context 內的單例，
     * 不清乾淨的話前一個測試留下的失敗次數會累加到下一個測試，尤其是本檔案裡
     * 好幾個測試都重覆使用 member@example.com／employee@example.com 這兩個 email。
     * 限流專屬測試都用自己專屬、不與其他測試重疊的 email，這裡只保險清掉本檔案
     * 既有測試會用到的兩個 email 與本機測試用的 IP。
     */
    @BeforeEach
    void clearRateLimiter() {
        rateLimiter.clear("login:email:member@example.com");
        rateLimiter.clear("login:email:employee@example.com");
        rateLimiter.clear("login:email:nobody@example.com");
        rateLimiter.clear("login:ip-minute:127.0.0.1");
        rateLimiter.clear("login:ip-hourly:127.0.0.1");
    }

    private Account account() {
        Account account = new Account();
        account.setId(7);
        account.setEmail("member@example.com");
        account.setPasswordHash(PASSWORD_HASH);
        account.getRoles().add(AccountType.MEMBER);
        return account;
    }

    private Account employeeAccount() {
        Account account = new Account();
        account.setId(9);
        account.setEmail("employee@example.com");
        account.setPasswordHash(PASSWORD_HASH);
        account.getRoles().add(AccountType.EMPLOYEE);
        return account;
    }

    private Member activeMember(Account account) {
        Member member = new Member();
        member.setAccount(account);
        member.setId(70);
        member.setStatus(UserStatus.ACTIVE);
        return member;
    }

    private Employee employee(Account account, EmployeeStatus status) {
        Employee employee = new Employee();
        employee.setAccount(account);
        employee.setId(90);
        employee.setStatus(status);
        return employee;
    }

    private String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void 正確帳密登入成功並回傳token與帳號資訊() throws Exception {
        Account account = account();
        Mockito.when(accounts.findByEmailIgnoreCase("member@example.com"))
                .thenReturn(Optional.of(account));
        Mockito.when(members.findByAccountId(7)).thenReturn(Optional.of(activeMember(account)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("member@example.com", RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.accountId").value(7))
                .andExpect(jsonPath("$.email").value("member@example.com"))
                // roles 內容不在此斷言：mock bean 為共用單例，member 反查的 stub 會跨測試
                // 洩漏（依執行順序可能是 [] 或 [MEMBER]），內容驗證見 token 迴圈測試
                .andExpect(jsonPath("$.roles").isArray());
    }

    @Test
    void 登入拿到的token可通過JwtAuthFilter存取受保護端點() throws Exception {
        Account account = account();
        Mockito.when(accounts.findByEmailIgnoreCase("member@example.com"))
                .thenReturn(Optional.of(account));
        // token 送回來時 JwtAuthFilter 會回查帳號與會員狀態
        Mockito.when(accounts.findById(7)).thenReturn(Optional.of(account));
        Member member = new Member();
        member.setAccount(account);
        member.setId(70);
        member.setStatus(UserStatus.ACTIVE);
        Mockito.when(members.findByAccountId(7)).thenReturn(Optional.of(member));

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("member@example.com", RAW_PASSWORD)))
                .andExpect(status().isOk())
                // 會員 ACTIVE：展開後的權限清單供前端導流
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(response, "$.accessToken");

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void 員工帳號PENDING_APPROVAL時回401且訊息提示尚待審核() throws Exception {
        Account account = employeeAccount();
        Mockito.when(accounts.findByEmailIgnoreCase("employee@example.com")).thenReturn(Optional.of(account));
        Mockito.when(employees.findByAccountId(9))
                .thenReturn(Optional.of(employee(account, EmployeeStatus.PENDING_APPROVAL)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("employee@example.com", RAW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("員工帳號尚待審核，請等候通知。"));
    }

    @Test
    void 員工帳號REJECTED時回401且訊息提示可重新申請() throws Exception {
        Account account = employeeAccount();
        Mockito.when(accounts.findByEmailIgnoreCase("employee@example.com")).thenReturn(Optional.of(account));
        Mockito.when(employees.findByAccountId(9))
                .thenReturn(Optional.of(employee(account, EmployeeStatus.REJECTED)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("employee@example.com", RAW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("員工申請未通過，如需重新申請請使用重新申請功能。"));
    }

    @Test
    void ACTIVE員工正常登入() throws Exception {
        Account account = employeeAccount();
        Mockito.when(accounts.findByEmailIgnoreCase("employee@example.com")).thenReturn(Optional.of(account));
        Mockito.when(employees.findByAccountId(9))
                .thenReturn(Optional.of(employee(account, EmployeeStatus.ACTIVE)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("employee@example.com", RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void 同帳號會員ACTIVE員工尚待審核時仍可用會員身分登入() throws Exception {
        Account account = account(); // MEMBER role, id=7
        Mockito.when(accounts.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(account));
        Mockito.when(members.findByAccountId(7)).thenReturn(Optional.of(activeMember(account)));
        Mockito.when(employees.findByAccountId(7))
                .thenReturn(Optional.of(employee(account, EmployeeStatus.PENDING_APPROVAL)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("member@example.com", RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void 密碼錯誤回401統一錯誤格式() throws Exception {
        Mockito.when(accounts.findByEmailIgnoreCase("member@example.com"))
                .thenReturn(Optional.of(account()));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("member@example.com", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("帳號或密碼錯誤"));
    }

    @Test
    void 查無帳號回401且訊息與密碼錯誤相同不洩漏帳號存在性() throws Exception {
        Mockito.when(accounts.findByEmailIgnoreCase("nobody@example.com"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody@example.com", RAW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("帳號或密碼錯誤"));
    }

    @Test
    void 軟刪除帳號視同不存在回401() throws Exception {
        Account deleted = account();
        deleted.setDeletedAt(LocalDateTime.now());
        Mockito.when(accounts.findByEmailIgnoreCase("member@example.com"))
                .thenReturn(Optional.of(deleted));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("member@example.com", RAW_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void email格式錯誤回400驗證失敗() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("not-an-email", RAW_PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 空白密碼回400驗證失敗() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("member@example.com", "")))
                .andExpect(status().isBadRequest());
    }

    // === 登入限流（AuthController.LOGIN_EMAIL_MAX_ATTEMPTS=5／15分鐘、IP 10/分鐘、30/小時） ===

    @Test
    void 連續5次密碼錯誤後第6次觸發429限流() throws Exception {
        Mockito.when(accounts.findByEmailIgnoreCase("member@example.com"))
                .thenReturn(Optional.of(account()));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("member@example.com", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("member@example.com", "wrong-password")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }

    @Test
    void 觸發限流後即使密碼正確仍回429() throws Exception {
        Mockito.when(accounts.findByEmailIgnoreCase("member@example.com"))
                .thenReturn(Optional.of(account()));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("member@example.com", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }

        // 限流檢查在密碼比對之前就擋下，第 6 次改用正確密碼也救不回來
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("member@example.com", RAW_PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void 登入成功會清空該email的失敗計數() throws Exception {
        Account account = account();
        Mockito.when(accounts.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(account));
        Mockito.when(members.findByAccountId(7)).thenReturn(Optional.of(activeMember(account)));

        // 3 次錯誤，未達 5 次門檻
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("member@example.com", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }

        // 密碼答對，應清空這個 email 的失敗計數
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("member@example.com", RAW_PASSWORD)))
                .andExpect(status().isOk());

        // 再錯 3 次：若沒清空，累計失敗次數會是 3+3=6，早該觸發限流；
        // 這裡預期仍是 401，證明計數確實從 0 重新算
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("member@example.com", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void 不同email的失敗計數互不影響() throws Exception {
        Mockito.when(accounts.findByEmailIgnoreCase("member@example.com"))
                .thenReturn(Optional.of(account()));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("member@example.com", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("member@example.com", "wrong-password")))
                .andExpect(status().isTooManyRequests());

        // employee@example.com 完全沒被牽連，第一次錯誤密碼仍正常回401（不是429）
        Mockito.when(accounts.findByEmailIgnoreCase("employee@example.com"))
                .thenReturn(Optional.of(employeeAccount()));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("employee@example.com", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 同IP大量嘗試即使各自email不同仍觸發IP層限流() throws Exception {
        // AuthController.LOGIN_IP_MINUTE_MAX = 10；每次用不同 email，證明擋下的是 IP 層而非 email 層
        for (int i = 0; i < 10; i++) {
            String email = "ip-rate-limit-test-" + i + "@example.com";
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(email, "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ip-rate-limit-test-final@example.com", "wrong-password")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }
}
