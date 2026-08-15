package tw.com.ispan.gym.controller.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Optional;

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
        "app.jwt.access-token-ttl=PT2H"
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
        ApiErrorWriter.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        tw.com.ispan.gym.config.SecurityConfig.class
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
}
