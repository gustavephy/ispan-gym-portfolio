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
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;
import tw.com.ispan.gym.service.employee.AdminEmployeeService;
import tw.com.ispan.gym.service.employee.EmployeeProfileService;

/**
 * 隔離驗證 AdminEmployeeController 六個方法上的 @PreAuthorize("hasRole('ADMIN')")，
 * 以及 EmployeeProfileController 兩個方法上的 @PreAuthorize("hasRole('EMPLOYEE')")。
 *
 * <p>為什麼需要獨立一支、且兩個 controller 合併在同一支：正式 SecurityConfig 對
 * /api/admin/** 與 /api/employee/** 都已有對應的 hasRole(...) URL 規則，filter 階段就會
 * 擋下不符資格的請求，@PreAuthorize 永遠執行不到——所以 EndpointAuthorizationTest 那邊
 * 的授權測試其實只證明了 URL 這一層。這支改用「測試專用」的 Security 設定，把兩個前綴
 * 在 filter 階段都放寬成僅要求 authenticated，讓 @PreAuthorize 成為唯一可能產生 403 的
 * 關卡；controller 本身用的是正牌實作，沒有被動過。這不會修改正式 SecurityConfig，
 * 測試專用設定只存在於這支測試的 Spring Context 裡。
 *
 * <p>兩個 controller 合併成一支而非各自獨立：Employee 自助 profile 的 hasRole('EMPLOYEE')
 * 是這次規格特別拍板、刻意跟 Member 自助 GET 用 .authenticated() 不同的行為（INACTIVE
 * 員工必須完全看不到自己資料），值得單獨釘住這個邊界，不能只靠 Service 層的防禦性檢查
 * 跟 URL 層間接覆蓋；為了控制測試檔案數量，跟 AdminEmployeeController 的方法層驗證放在
 * 同一支（Member 對應的 MemberProfileController 沒有這樣的獨立測試）。
 *
 * <p>EndpointAuthorizationTest 與本測試分工：前者驗 URL 層（真牌 SecurityConfig），
 * 後者驗方法層（放寬 URL 層、真牌 @PreAuthorize），兩者合起來才涵蓋完整授權路徑。
 */
@SpringBootTest(
        classes = {AdminEmployeeMethodSecurityTest.TestApp.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.profiles.active=test",
        "app.jwt.secret=dGVzdC1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTYtc2lnbmluZyEh",
        "app.jwt.issuer=ispan-gym",
        "app.jwt.access-token-ttl=PT2H"
})
class AdminEmployeeMethodSecurityTest {

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
        tw.com.ispan.gym.controller.employee.AdminEmployeeController.class,
        tw.com.ispan.gym.controller.employee.EmployeeProfileController.class,
        JwtUtil.class,
        JwtAuthFilter.class,
        AccountAuthorityResolver.class,
        UnconfiguredCurrentUserProvider.class,
        ApiErrorWriter.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        tw.com.ispan.gym.exception.GlobalExceptionHandler.class
    })
    static class TestApp {}

    /**
     * 測試專用：/api/admin/employees/** 與 /api/employee/** 在 URL 層都只要求登入，
     * 刻意「不」設對應的 hasRole(...)。正式環境兩層都有，這裡拿掉外層是為了讓內層
     * （方法層 @PreAuthorize）單獨受檢。
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
                            // 關鍵差異：正式設定這幾條分別是 hasRole("ADMIN") 與 hasRole("EMPLOYEE")
                            .requestMatchers(HttpMethod.GET, "/api/admin/employees/**").authenticated()
                            .requestMatchers(HttpMethod.PUT, "/api/admin/employees/**").authenticated()
                            .requestMatchers(HttpMethod.POST, "/api/admin/employees/**").authenticated()
                            .requestMatchers(HttpMethod.GET, "/api/employee/profile").authenticated()
                            .requestMatchers(HttpMethod.PUT, "/api/employee/profile").authenticated()
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
        @Bean @Primary OauthAccountRepository oauthAccountRepository() {
            return Mockito.mock(OauthAccountRepository.class);
        }
        @Bean @Primary AdminEmployeeService adminEmployeeService() {
            return Mockito.mock(AdminEmployeeService.class);
        }
        @Bean @Primary EmployeeProfileService employeeProfileService() {
            return Mockito.mock(EmployeeProfileService.class);
        }
        // EmployeeProfileController 自 feat/private-course-improvements 起注入 FileService（頭像上傳）
        @Bean @Primary tw.com.ispan.gym.service.file.FileService fileService() {
            return Mockito.mock(tw.com.ispan.gym.service.file.FileService.class);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired AccountRepository accounts;
    @Autowired MemberRepository members;
    @Autowired EmployeeRepository employees;
    @Autowired TrainerRepository trainers;
    @Autowired AdminEmployeeService adminEmployeeService;
    @Autowired EmployeeProfileService employeeProfileService;

    /**
     * mock 是 Spring context 裡的共用單例，Mockito 不會在測試方法之間自動重置——
     * 少了這段，前一個方法留下的呼叫紀錄會被下一個方法的 verify(never()) 讀到，
     * 「Service 沒被呼叫」的斷言就只是碰巧成立（取決於執行順序）。
     */
    @BeforeEach
    void resetMocks() {
        Mockito.reset(adminEmployeeService, employeeProfileService, accounts, members, employees, trainers);
    }

    private static final String VALID_UPDATE_BODY = """
            {
              "firstName": "小美",
              "lastName": "王",
              "phone": "0912345678",
              "hireDate": "2020-01-01"
            }
            """;

    private static final String VALID_ROLE_BODY = """
            { "role": "ADMIN" }
            """;

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

    /** 純會員，沒有任何員工身分——用來證明 hasRole('EMPLOYEE') 本身確實會擋下非員工 */
    private String memberToken() {
        Account account = new Account();
        account.setId(1);
        account.setEmail("member@example.com");
        account.getRoles().add(AccountType.MEMBER);
        Mockito.when(accounts.findById(1)).thenReturn(Optional.of(account));
        Mockito.when(accounts.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(account));

        Member member = new Member();
        member.setId(11);
        member.setAccount(account);
        member.setStatus(UserStatus.ACTIVE);
        Mockito.when(members.findByAccountId(1)).thenReturn(Optional.of(member));

        return jwtUtil.generateToken(account);
    }

    // === GET /api/admin/employees ===================================================

    @Test
    void 員工列表_一般員工被方法層級擋下且Service未被呼叫() throws Exception {
        mockMvc.perform(get("/api/admin/employees")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminEmployeeService, Mockito.never())
                .list(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(),
                        Mockito.any(), Mockito.any());
    }

    @Test
    void 員工列表_管理者通過方法層級且Service被呼叫() throws Exception {
        mockMvc.perform(get("/api/admin/employees")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminEmployeeService)
                .list(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(),
                        Mockito.any(), Mockito.any());
    }

    /** 對照組：URL 層已放寬成 authenticated，未登入仍必須是 401 而不是放行 */
    @Test
    void 員工列表_未登入回401() throws Exception {
        mockMvc.perform(get("/api/admin/employees"))
                .andExpect(status().isUnauthorized());
    }

    // === GET /api/admin/employees/{employeeId} ======================================

    @Test
    void 單一員工查詢_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(get("/api/admin/employees/1")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminEmployeeService, Mockito.never()).getById(Mockito.any());
    }

    @Test
    void 單一員工查詢_管理者通過方法層級() throws Exception {
        mockMvc.perform(get("/api/admin/employees/1")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminEmployeeService).getById(1);
    }

    // === PUT /api/admin/employees/{employeeId} ======================================

    @Test
    void 代改個資_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(put("/api/admin/employees/1")
                        .header("Authorization", "Bearer " + employeeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_UPDATE_BODY))
                .andExpect(status().isForbidden());

        Mockito.verify(adminEmployeeService, Mockito.never()).update(Mockito.any(), Mockito.any());
    }

    @Test
    void 代改個資_管理者通過方法層級() throws Exception {
        mockMvc.perform(put("/api/admin/employees/1")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_UPDATE_BODY))
                .andExpect(status().isOk());

        Mockito.verify(adminEmployeeService).update(Mockito.eq(1), Mockito.any());
    }

    // === POST /api/admin/employees/{employeeId}/role ================================

    @Test
    void 角色調整_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(post("/api/admin/employees/1/role")
                        .header("Authorization", "Bearer " + employeeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ROLE_BODY))
                .andExpect(status().isForbidden());

        Mockito.verify(adminEmployeeService, Mockito.never()).changeRole(Mockito.any(), Mockito.any());
    }

    @Test
    void 角色調整_管理者通過方法層級() throws Exception {
        mockMvc.perform(post("/api/admin/employees/1/role")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ROLE_BODY))
                .andExpect(status().isOk());

        Mockito.verify(adminEmployeeService).changeRole(Mockito.eq(1), Mockito.any());
    }

    // === POST /api/admin/employees/{employeeId}/suspend =============================

    @Test
    void 停用_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(post("/api/admin/employees/1/suspend")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminEmployeeService, Mockito.never()).suspend(Mockito.any());
    }

    @Test
    void 停用_管理者通過方法層級() throws Exception {
        mockMvc.perform(post("/api/admin/employees/1/suspend")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminEmployeeService).suspend(1);
    }

    // === POST /api/admin/employees/{employeeId}/activate ============================

    @Test
    void 恢復_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(post("/api/admin/employees/1/activate")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminEmployeeService, Mockito.never()).activate(Mockito.any());
    }

    @Test
    void 恢復_管理者通過方法層級() throws Exception {
        mockMvc.perform(post("/api/admin/employees/1/activate")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminEmployeeService).activate(1);
    }

    // === GET /api/employee/profile ===================================================

    @Test
    void 自助查詢_非員工身分被方法層級擋下() throws Exception {
        mockMvc.perform(get("/api/employee/profile")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(employeeProfileService, Mockito.never()).getMyProfile(Mockito.any());
    }

    @Test
    void 自助查詢_員工身分通過方法層級() throws Exception {
        mockMvc.perform(get("/api/employee/profile")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isOk());

        Mockito.verify(employeeProfileService).getMyProfile(22);
    }

    /** 對照組：URL 層已放寬成 authenticated，未登入仍必須是 401 而不是放行 */
    @Test
    void 自助查詢_未登入回401() throws Exception {
        mockMvc.perform(get("/api/employee/profile"))
                .andExpect(status().isUnauthorized());
    }

    // === PUT /api/employee/profile ===================================================

    @Test
    void 自助修改_非員工身分被方法層級擋下() throws Exception {
        mockMvc.perform(put("/api/employee/profile")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { 
                                    "firstName": "小美",
                                    "lastName": "王",
                                    "phone": "0912345678" 
                                }
                                """))
                .andExpect(status().isForbidden());

        Mockito.verify(employeeProfileService, Mockito.never()).updateMyProfile(Mockito.any(), Mockito.any());
    }

    @Test
    void 自助修改_員工身分通過方法層級() throws Exception {
        mockMvc.perform(put("/api/employee/profile")
                        .header("Authorization", "Bearer " + employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { 
                                    "firstName": "小美",
                                    "lastName": "王",
                                    "phone": "0912345678" 
                                }
                                """))
                .andExpect(status().isOk());

        Mockito.verify(employeeProfileService).updateMyProfile(Mockito.eq(2), Mockito.any());
    }
}
