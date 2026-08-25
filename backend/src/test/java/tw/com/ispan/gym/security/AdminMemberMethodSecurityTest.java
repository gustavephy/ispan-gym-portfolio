package tw.com.ispan.gym.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;
import tw.com.ispan.gym.service.coach.CoachBookingService;
import tw.com.ispan.gym.service.member.AdminMemberService;

/**
 * 隔離驗證 AdminMemberController 六個方法上的 @PreAuthorize("hasRole('ADMIN')")。
 *
 * <p>為什麼需要獨立一支：正式 SecurityConfig 對 /api/admin/** 有 hasRole("ADMIN") 的 URL
 * 規則，filter 階段就會擋下非管理者，@PreAuthorize 永遠執行不到——所以
 * {@link EndpointAuthorizationTest} 那邊「會員列表僅限管理者」等測試其實只證明了 URL 這一層。
 * 這支改用「測試專用」的 Security 設定，把 /api/admin/members/** 在 filter 階段放寬成僅要求
 * authenticated，讓 @PreAuthorize 成為唯一可能產生 403 的關卡；controller 本身用的是正牌實作，
 * 沒有被動過。這不會修改正式 SecurityConfig，測試專用設定只存在於這支測試的 Spring Context 裡。
 *
 * <p>正式 SecurityConfig 不在這支的 @Import 清單內（TestApp 不做 component scan），
 * 因此不會有兩條 filter chain 互相干擾，也不需要為了測試去改動正式設定。
 *
 * <p>EndpointAuthorizationTest 與本測試分工：前者驗 URL 層（真牌 SecurityConfig），
 * 後者驗方法層（放寬 URL 層、真牌 @PreAuthorize），兩者合起來才涵蓋完整授權路徑。
 */
@SpringBootTest(
        classes = {AdminMemberMethodSecurityTest.TestApp.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.profiles.active=test",
        "app.jwt.secret=dGVzdC1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTYtc2lnbmluZyEh",
        "app.jwt.issuer=ispan-gym",
        "app.jwt.access-token-ttl=PT2H"
})
class AdminMemberMethodSecurityTest {

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
        // 受測的正牌 controller，未做任何調整
        tw.com.ispan.gym.controller.member.AdminMemberController.class,
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
     * 測試專用：/api/admin/members/** 在 URL 層只要求登入，刻意「不」設 hasRole("ADMIN")。
     * 正式環境兩層都有，這裡拿掉外層是為了讓內層（方法層 @PreAuthorize）單獨受檢。
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
                            .requestMatchers(HttpMethod.GET, "/api/admin/members/**").authenticated()
                            .requestMatchers(HttpMethod.PUT, "/api/admin/members/**").authenticated()
                            .requestMatchers(HttpMethod.POST, "/api/admin/members/**").authenticated()
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
        @Bean @Primary AdminMemberService adminMemberService() {
            return Mockito.mock(AdminMemberService.class);
        }
        @Bean @Primary CoachBookingService coachBookingService() {
            return Mockito.mock(CoachBookingService.class);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired AccountRepository accounts;
    @Autowired EmployeeRepository employees;
    @Autowired TrainerRepository trainers;
    @Autowired AdminMemberService adminMemberService;
    @Autowired CoachBookingService coachBookingService;

    private static final String VALID_UPDATE_BODY = """
            {
              "firstName": "小美",
              "lastName": "王",
              "phone": "0912345678"
            }
            """;

    /**
     * mock 是 Spring context 裡的共用單例，Mockito 不會在測試方法之間自動重置——
     * 少了這段，前一個方法留下的呼叫紀錄會被下一個方法的 verify(never()) 讀到，
     * 「Service 沒被呼叫」的斷言就只是碰巧成立（取決於執行順序）。
     */
    @BeforeEach
    void resetMocks() {
        Mockito.reset(adminMemberService, coachBookingService, accounts, employees, trainers);
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

    private String employeeToken() {
        return tokenFor(2, 22, "employee@example.com", EmployeeRole.EMPLOYEE);
    }

    private String adminToken() {
        return tokenFor(3, 33, "admin@example.com", EmployeeRole.ADMIN);
    }

    // === GET /api/admin/members ====================================================

    @Test
    void 會員列表_一般員工被方法層級擋下且Service未被呼叫() throws Exception {
        mockMvc.perform(get("/api/admin/members")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminMemberService, Mockito.never())
                .list(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.any(), Mockito.any());
    }

    @Test
    void 會員列表_管理者通過方法層級且Service被呼叫() throws Exception {
        mockMvc.perform(get("/api/admin/members")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminMemberService)
                .list(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.any(), Mockito.any());
    }

    /** 對照組：URL 層已放寬成 authenticated，未登入仍必須是 401 而不是放行 */
    @Test
    void 會員列表_未登入回401() throws Exception {
        mockMvc.perform(get("/api/admin/members"))
                .andExpect(status().isUnauthorized());
    }

    // === GET /api/admin/members/{memberId} =========================================

    @Test
    void 單一會員查詢_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(get("/api/admin/members/1")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminMemberService, Mockito.never()).getById(Mockito.any());
    }

    @Test
    void 單一會員查詢_管理者通過方法層級() throws Exception {
        mockMvc.perform(get("/api/admin/members/1")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminMemberService).getById(1);
    }

    // === PUT /api/admin/members/{memberId} =========================================

    @Test
    void 代改個資_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(put("/api/admin/members/1")
                        .header("Authorization", "Bearer " + employeeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_UPDATE_BODY))
                .andExpect(status().isForbidden());

        Mockito.verify(adminMemberService, Mockito.never()).update(Mockito.any(), Mockito.any());
    }

    @Test
    void 代改個資_管理者通過方法層級() throws Exception {
        mockMvc.perform(put("/api/admin/members/1")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_UPDATE_BODY))
                .andExpect(status().isOk());

        Mockito.verify(adminMemberService).update(Mockito.eq(1), Mockito.any());
    }

    // === POST /api/admin/members/{memberId}/suspend ================================

    @Test
    void 停用_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(post("/api/admin/members/1/suspend")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminMemberService, Mockito.never()).suspend(Mockito.any());
    }

    @Test
    void 停用_管理者通過方法層級() throws Exception {
        mockMvc.perform(post("/api/admin/members/1/suspend")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminMemberService).suspend(1);
    }

    // === POST /api/admin/members/{memberId}/activate ================================

    @Test
    void 恢復_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(post("/api/admin/members/1/activate")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminMemberService, Mockito.never()).activate(Mockito.any());
    }

    @Test
    void 恢復_管理者通過方法層級() throws Exception {
        mockMvc.perform(post("/api/admin/members/1/activate")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminMemberService).activate(1);
    }

    // === GET /api/admin/members/{memberId}/course-quotas ===========================

    @Test
    void 會員額度查詢_一般員工被方法層級擋下且Service未被呼叫() throws Exception {
        mockMvc.perform(get("/api/admin/members/1/course-quotas")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(coachBookingService, Mockito.never()).getMyQuotas(Mockito.any());
    }

    @Test
    void 會員額度查詢_管理者通過方法層級且Service被呼叫() throws Exception {
        mockMvc.perform(get("/api/admin/members/1/course-quotas")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(coachBookingService).getMyQuotas(1);
    }
}
