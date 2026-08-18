package tw.com.ispan.gym.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tw.com.ispan.gym.dto.trainer.TrainerCreateRequest;
import tw.com.ispan.gym.dto.trainer.TrainerResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;

/**
 * 隔離驗證 TrainerController#create 的方法層級 @PreAuthorize("hasRole('ADMIN')")。
 *
 * <p>為什麼需要獨立一支：正式 SecurityConfig 對 POST /api/employees/*&#47;trainer 有
 * hasRole("ADMIN") 的 URL 規則，filter 階段就會擋下非管理者，@PreAuthorize 永遠執行不到——
 * 所以 {@link EndpointAuthorizationTest} 那邊的 403 其實只證明了 URL 這一層。
 * 這支改用「測試專用」的 Security 設定，把該路徑在 filter 階段放寬成僅要求 authenticated，
 * 讓 @PreAuthorize 成為唯一可能產生 403 的關卡；controller 本身用的是正牌實作，沒有被動過。
 *
 * <p>正式 SecurityConfig 不在這支的 @Import 清單內（TestApp 不做 component scan），
 * 因此不會有兩條 filter chain 互相干擾，也不需要為了測試去改動正式設定。
 */
@SpringBootTest(
        classes = {TrainerMethodSecurityTest.TestApp.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.profiles.active=test",
        "app.jwt.secret=dGVzdC1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTYtc2lnbmluZyEh",
        "app.jwt.issuer=ispan-gym",
        "app.jwt.access-token-ttl=PT2H"
})
class TrainerMethodSecurityTest {

    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(
            exclude = {
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class
            })
    @org.springframework.context.annotation.Configuration
    @Import({
        MockedBeans.class,
        RelaxedUrlSecurityConfig.class,
        JwtUtil.class,
        JwtAuthFilter.class,
        AccountAuthorityResolver.class,
        ApiErrorWriter.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        tw.com.ispan.gym.exception.GlobalExceptionHandler.class
    })
    static class TestApp {}

    /**
     * 測試專用：URL 層只要求登入，刻意「不」設 hasRole("ADMIN")。
     * 正式環境兩層都有，這裡拿掉外層是為了讓內層單獨受檢。
     */
    @EnableWebSecurity
    @EnableMethodSecurity
    @org.springframework.context.annotation.Configuration
    static class RelaxedUrlSecurityConfig {

        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http,
                JwtAuthFilter jwtAuthFilter,
                JsonAuthenticationEntryPoint authenticationEntryPoint,
                JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
            http
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            // 關鍵差異：正式設定這條是 hasRole("ADMIN")
                            .requestMatchers(HttpMethod.POST, "/api/employees/*/trainer").authenticated()
                            .anyRequest().permitAll())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(authenticationEntryPoint)
                            .accessDeniedHandler(accessDeniedHandler))
                    .httpBasic(basic -> basic.disable())
                    .formLogin(form -> form.disable())
                    .logout(logout -> logout.disable())
                    .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }
    }

    @TestConfiguration
    static class MockedBeans {
        @Bean @Primary AccountRepository accountRepository() {
            return Mockito.mock(AccountRepository.class);
        }
        @Bean @Primary MemberRepository memberRepository() {
            return Mockito.mock(MemberRepository.class);
        }
        @Bean @Primary EmployeeRepository employeeRepository() {
            return Mockito.mock(EmployeeRepository.class);
        }
        @Bean @Primary TrainerRepository trainerRepository() {
            return Mockito.mock(TrainerRepository.class);
        }
        @Bean @Primary TrainerService trainerService() {
            return Mockito.mock(TrainerService.class);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired AccountRepository accounts;
    @Autowired EmployeeRepository employees;
    @Autowired TrainerRepository trainers;
    @Autowired TrainerService trainerService;

    private static final String BODY = """
            {
              "specialty": "重量訓練",
              "experience": "五年授課經驗",
              "bio": "自我介紹"
            }
            """;

    /**
     * mock 是 Spring context 裡的共用單例，Mockito 不會在測試方法之間自動重置——
     * 少了這段，前一個方法留下的呼叫紀錄會被下一個方法的 verify(never()) 讀到，
     * 「Service 沒被呼叫」的斷言就只是碰巧成立（取決於執行順序）。
     */
    @BeforeEach
    void resetMocks() {
        Mockito.reset(trainerService, accounts, employees, trainers);
    }

    private String tokenFor(int accountId, int employeeId, String email, EmployeeRole role) {
        Account account = new Account();
        account.setId(accountId);
        account.setEmail(email);
        account.getRoles().add(AccountType.EMPLOYEE);
        Mockito.when(accounts.findById(accountId)).thenReturn(Optional.of(account));
        Mockito.when(accounts.findByEmailIgnoreCase(email)).thenReturn(Optional.of(account));

        Employee employee = new Employee();
        employee.setId(employeeId);
        employee.setAccount(account);
        employee.setRole(role);
        employee.setStatus(EmployeeStatus.ACTIVE);
        Mockito.when(employees.findByAccountId(accountId)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(employeeId)).thenReturn(Optional.empty());

        return jwtUtil.generateToken(account);
    }

    @Test
    void 一般員工被方法層級授權擋下且Service完全沒被呼叫() throws Exception {
        String token = tokenFor(2, 22, "employee@example.com", EmployeeRole.EMPLOYEE);

        mockMvc.perform(post("/api/employees/22/trainer")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        // URL 層已放行（只要求登入），403 只可能來自 @PreAuthorize；
        // 並確認請求沒有滲進業務邏輯
        Mockito.verify(trainerService, Mockito.never()).create(any(), any());
    }

    @Test
    void 管理者通過方法層級授權且Service確實被呼叫() throws Exception {
        String token = tokenFor(3, 33, "admin@example.com", EmployeeRole.ADMIN);
        Mockito.when(trainerService.create(eq(22), any(TrainerCreateRequest.class)))
                .thenReturn(new TrainerResponse(7, "陳小華", "重量訓練", "五年授課經驗",
                        "自我介紹", TrainerStatus.ACTIVE));

        mockMvc.perform(post("/api/employees/22/trainer")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        Mockito.verify(trainerService).create(eq(22), any(TrainerCreateRequest.class));
    }

    /** 對照組：URL 層已放寬成 authenticated，未登入仍必須是 401 而不是放行 */
    @Test
    void 未登入回401() throws Exception {
        mockMvc.perform(post("/api/employees/22/trainer")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        Mockito.verify(trainerService, Mockito.never()).create(any(), any());
    }
}
