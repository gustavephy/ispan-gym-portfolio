package tw.com.ispan.gym.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 驗證 filter 層的 401／403 與 controller 層回傳相同的 ApiErrorResponse 格式。
 * 不連資料庫，MemberRepository 以 mock 取代。
 */
@SpringBootTest(
        classes = {FilterErrorFormatTest.TestApp.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.jwt.secret=dGVzdC1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTYtc2lnbmluZyEh",
        "app.jwt.issuer=ispan-gym",
        "app.jwt.access-token-ttl=PT2H"
})
class FilterErrorFormatTest {

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
        JwtUtil.class,
        JwtAuthFilter.class,
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
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/orders")
        String secured() {
            return "ok";
        }

        @GetMapping("/api/admin/ping")
        String adminOnly() {
            return "ok";
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired AccountRepository accounts;
    @Autowired MemberRepository members;

    /** 只有 MEMBER 身分的帳號：可通過認證，但進不了 /api/admin/** */
    private Account memberAccount() {
        Account account = new Account();
        account.setId(7);
        account.setEmail("member@example.com");
        account.setPasswordHash("{noop}irrelevant");
        account.getRoles().add(AccountType.MEMBER);

        Member member = new Member();
        member.setAccount(account);
        member.setId(70); // member_id 與 account_id 已脫鉤，刻意不同號
        member.setStatus(UserStatus.ACTIVE);

        Mockito.when(accounts.findById(7)).thenReturn(Optional.of(account));
        Mockito.when(members.findByAccountId(7)).thenReturn(Optional.of(member));
        return account;
    }

    @Test
    void 未登入時回傳ApiErrorResponse格式的401() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void 權限不足時回傳ApiErrorResponse格式的403() throws Exception {
        String token = jwtUtil.generateToken(memberAccount());

        mockMvc.perform(get("/api/admin/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * timestamp 必須是 ISO 字串，不能是 [2026,8,10,...] 這種數字陣列，
     * 否則與 controller 層的 ApiErrorResponse 格式不一致。
     */
    @Test
    void timestamp序列化為ISO字串而非數字陣列() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.timestamp").isString());
    }

    /** 中文訊息不可因缺少 charset 而亂碼 */
    @Test
    void 中文訊息以UTF8正確輸出() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("請先登入後再執行此操作"));
    }
}
