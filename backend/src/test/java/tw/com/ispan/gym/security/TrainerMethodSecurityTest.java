package tw.com.ispan.gym.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import tw.com.ispan.gym.dto.trainer.AdminTrainerResponse;
import tw.com.ispan.gym.dto.trainer.TrainerCreateRequest;
import tw.com.ispan.gym.dto.trainer.TrainerProfileResponse;
import tw.com.ispan.gym.dto.trainer.TrainerResponse;
import tw.com.ispan.gym.dto.trainer.TrainerSuspensionCheckResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;
import tw.com.ispan.gym.service.trainer.AdminTrainerService;
import tw.com.ispan.gym.service.trainer.TrainerProfileService;
import tw.com.ispan.gym.service.trainer.TrainerService;

/**
 * 隔離驗證三個 Trainer 相關 controller 的方法層級 @PreAuthorize：
 * TrainerController#create（hasRole('ADMIN')）、TrainerProfileController 兩個方法
 * （hasRole('TRAINER')）、AdminTrainerController 七個方法（hasRole('ADMIN')）。
 *
 * <p>為什麼需要獨立一支：正式 SecurityConfig 對這幾條路徑都已有對應的 hasRole(...) URL
 * 規則，filter 階段就會擋下不符資格的請求，@PreAuthorize 永遠執行不到——所以
 * {@link EndpointAuthorizationTest} 那邊的 403 其實只證明了 URL 這一層。這支改用「測試專用」
 * 的 Security 設定，把這幾條路徑在 filter 階段都放寬成僅要求 authenticated，讓
 * @PreAuthorize 成為唯一可能產生 403 的關卡；controller 本身用的是正牌實作，沒有被動過。
 *
 * <p>三個 controller 合併成一支而非各自獨立，比照 AdminEmployeeMethodSecurityTest 合併
 * AdminEmployeeController／EmployeeProfileController 的理由：控制測試檔案數量。
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
        // 受測的正牌 controller，未做任何調整
        tw.com.ispan.gym.controller.trainer.TrainerController.class,
        tw.com.ispan.gym.controller.trainer.TrainerProfileController.class,
        tw.com.ispan.gym.controller.trainer.AdminTrainerController.class,
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
     * 測試專用：URL 層只要求登入，刻意「不」設 hasRole(...)。
     * 正式環境三條路徑都各自有對應的 hasRole(...)，這裡拿掉外層是為了讓內層單獨受檢。
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
                            // 關鍵差異：正式設定這幾條分別是 hasRole("ADMIN") 與 hasRole("TRAINER")
                            .requestMatchers(HttpMethod.POST, "/api/employees/*/trainer").authenticated()
                            .requestMatchers(HttpMethod.GET, "/api/trainer/profile").authenticated()
                            .requestMatchers(HttpMethod.PUT, "/api/trainer/profile").authenticated()
                            .requestMatchers("/api/admin/trainers/**").authenticated()
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
        @Bean @Primary TrainerService trainerService() {
            return Mockito.mock(TrainerService.class);
        }
        @Bean @Primary TrainerProfileService trainerProfileService() {
            return Mockito.mock(TrainerProfileService.class);
        }
        @Bean @Primary AdminTrainerService adminTrainerService() {
            return Mockito.mock(AdminTrainerService.class);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired AccountRepository accounts;
    @Autowired EmployeeRepository employees;
    @Autowired TrainerRepository trainers;
    @Autowired TrainerService trainerService;
    @Autowired TrainerProfileService trainerProfileService;
    @Autowired AdminTrainerService adminTrainerService;

    private static final String CREATE_BODY = """
            {
              "specialty": "重量訓練",
              "experience": "五年授課經驗",
              "bio": "自我介紹"
            }
            """;

    private static final String PROFILE_UPDATE_BODY = """
            {
              "specialty": "重量訓練",
              "experience": "五年授課經驗",
              "bio": "自我介紹"
            }
            """;

    private static final String ADMIN_UPDATE_BODY = """
            {
              "specialty": "重量訓練",
              "experience": "五年授課經驗",
              "bio": "自我介紹"
            }
            """;

    private static final String STATUS_BODY = """
            { "status": "UNAVAILABLE" }
            """;

    /**
     * mock 是 Spring context 裡的共用單例，Mockito 不會在測試方法之間自動重置——
     * 少了這段，前一個方法留下的呼叫紀錄會被下一個方法的 verify(never()) 讀到，
     * 「Service 沒被呼叫」的斷言就只是碰巧成立（取決於執行順序）。
     */
    @BeforeEach
    void resetMocks() {
        Mockito.reset(trainerService, trainerProfileService, adminTrainerService, accounts, employees, trainers);
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

    /** 具有效 Trainer 身分（ACCOUNT_AUTHORITY_RESOLVER 只看 Trainer.deletedAt，不看 status） */
    private String trainerToken() {
        int accountId = 4;
        int employeeId = 44;
        Account account = new Account();
        account.setId(accountId);
        account.setEmail("trainer@example.com");
        account.getRoles().add(AccountType.EMPLOYEE);
        Mockito.when(accounts.findById(accountId)).thenReturn(Optional.of(account));
        Mockito.when(accounts.findByEmailIgnoreCase("trainer@example.com")).thenReturn(Optional.of(account));

        Employee employee = new Employee();
        employee.setId(employeeId);
        employee.setAccount(account);
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(EmployeeStatus.ACTIVE);
        Mockito.when(employees.findByAccountId(accountId)).thenReturn(Optional.of(employee));

        Trainer trainer = new Trainer();
        trainer.setId(77);
        trainer.setEmployee(employee);
        trainer.setStatus(TrainerStatus.ACTIVE);
        Mockito.when(trainers.findByEmployeeId(employeeId)).thenReturn(Optional.of(trainer));

        return jwtUtil.generateToken(account);
    }

    // === POST /api/employees/{employeeId}/trainer（既有，未變更）====================

    @Test
    void 建立教練_一般員工被方法層級授權擋下且Service完全沒被呼叫() throws Exception {
        String token = employeeToken();

        mockMvc.perform(post("/api/employees/22/trainer")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());

        Mockito.verify(trainerService, Mockito.never()).create(any(), any());
    }

    @Test
    void 建立教練_管理者通過方法層級授權且Service確實被呼叫() throws Exception {
        String token = adminToken();
        Mockito.when(trainerService.create(eq(22), any(TrainerCreateRequest.class)))
                .thenReturn(new TrainerResponse(7, "陳小華", "重量訓練", "五年授課經驗",
                        "自我介紹", null, TrainerStatus.ACTIVE));

        mockMvc.perform(post("/api/employees/22/trainer")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());

        Mockito.verify(trainerService).create(eq(22), any(TrainerCreateRequest.class));
    }

    @Test
    void 建立教練_未登入回401() throws Exception {
        mockMvc.perform(post("/api/employees/22/trainer")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized());

        Mockito.verify(trainerService, Mockito.never()).create(any(), any());
    }

    // === GET /api/trainer/profile ===================================================

    @Test
    void 教練本人查詢_一般員工無教練身分被方法層級擋下() throws Exception {
        mockMvc.perform(get("/api/trainer/profile")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(trainerProfileService, Mockito.never()).getMyProfile(any());
    }

    @Test
    void 教練本人查詢_有效教練通過方法層級() throws Exception {
        Mockito.when(trainerProfileService.getMyProfile(44)).thenReturn(
                new TrainerProfileResponse(77, 44, "重量訓練", "五年", "自我介紹", null, TrainerStatus.ACTIVE));

        mockMvc.perform(get("/api/trainer/profile")
                        .header("Authorization", "Bearer " + trainerToken()))
                .andExpect(status().isOk());

        Mockito.verify(trainerProfileService).getMyProfile(44);
    }

    @Test
    void 教練本人查詢_未登入回401() throws Exception {
        mockMvc.perform(get("/api/trainer/profile"))
                .andExpect(status().isUnauthorized());

        Mockito.verify(trainerProfileService, Mockito.never()).getMyProfile(any());
    }

    // === PUT /api/trainer/profile ===================================================

    @Test
    void 教練本人修改_一般員工無教練身分被方法層級擋下() throws Exception {
        mockMvc.perform(put("/api/trainer/profile")
                        .header("Authorization", "Bearer " + employeeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_UPDATE_BODY))
                .andExpect(status().isForbidden());

        Mockito.verify(trainerProfileService, Mockito.never()).updateMyProfile(any(), any());
    }

    @Test
    void 教練本人修改_有效教練通過方法層級() throws Exception {
        Mockito.when(trainerProfileService.updateMyProfile(eq(4), any())).thenReturn(
                new TrainerProfileResponse(77, 44, "重量訓練", "五年授課經驗", "自我介紹", null, TrainerStatus.ACTIVE));

        mockMvc.perform(put("/api/trainer/profile")
                        .header("Authorization", "Bearer " + trainerToken())
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_UPDATE_BODY))
                .andExpect(status().isOk());

        Mockito.verify(trainerProfileService).updateMyProfile(eq(4), any());
    }

    // === GET /api/admin/trainers ======================================================

    @Test
    void 教練列表_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(get("/api/admin/trainers")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminTrainerService, Mockito.never())
                .list(any(), any(), Mockito.anyBoolean(), Mockito.anyInt(), Mockito.anyInt(), any(), any());
    }

    @Test
    void 教練列表_管理者通過方法層級() throws Exception {
        mockMvc.perform(get("/api/admin/trainers")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminTrainerService)
                .list(any(), any(), Mockito.anyBoolean(), Mockito.anyInt(), Mockito.anyInt(), any(), any());
    }

    @Test
    void 教練列表_未登入回401() throws Exception {
        mockMvc.perform(get("/api/admin/trainers"))
                .andExpect(status().isUnauthorized());
    }

    // === GET /api/admin/trainers/{trainerId} ==========================================

    @Test
    void 教練單筆查詢_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(get("/api/admin/trainers/7")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminTrainerService, Mockito.never()).getById(any());
    }

    @Test
    void 教練單筆查詢_管理者通過方法層級() throws Exception {
        mockMvc.perform(get("/api/admin/trainers/7")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminTrainerService).getById(7);
    }

    // === PUT /api/admin/trainers/{trainerId} ==========================================

    @Test
    void 代改專業資料_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(put("/api/admin/trainers/7")
                        .header("Authorization", "Bearer " + employeeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ADMIN_UPDATE_BODY))
                .andExpect(status().isForbidden());

        Mockito.verify(adminTrainerService, Mockito.never()).update(any(), any());
    }

    @Test
    void 代改專業資料_管理者通過方法層級() throws Exception {
        mockMvc.perform(put("/api/admin/trainers/7")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(ADMIN_UPDATE_BODY))
                .andExpect(status().isOk());

        Mockito.verify(adminTrainerService).update(eq(7), any());
    }

    // === POST /api/admin/trainers/{trainerId}/status ==================================

    @Test
    void 狀態切換_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(post("/api/admin/trainers/7/status")
                        .header("Authorization", "Bearer " + employeeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(STATUS_BODY))
                .andExpect(status().isForbidden());

        Mockito.verify(adminTrainerService, Mockito.never()).changeStatus(any(), any());
    }

    @Test
    void 狀態切換_管理者通過方法層級() throws Exception {
        mockMvc.perform(post("/api/admin/trainers/7/status")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(STATUS_BODY))
                .andExpect(status().isOk());

        Mockito.verify(adminTrainerService).changeStatus(eq(7), any());
    }

    // === GET /api/admin/trainers/{trainerId}/suspension-check ==========================

    @Test
    void 停用預檢_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(get("/api/admin/trainers/7/suspension-check")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminTrainerService, Mockito.never()).suspensionCheck(any());
    }

    @Test
    void 停用預檢_管理者通過方法層級() throws Exception {
        Mockito.when(adminTrainerService.suspensionCheck(7)).thenReturn(
                new TrainerSuspensionCheckResponse(true, new TrainerSuspensionCheckResponse.Blockers(0, 0, 0, 0)));

        mockMvc.perform(get("/api/admin/trainers/7/suspension-check")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminTrainerService).suspensionCheck(7);
    }

    // === POST /api/admin/trainers/{trainerId}/suspend ===================================

    @Test
    void 停用_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(post("/api/admin/trainers/7/suspend")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminTrainerService, Mockito.never()).suspend(any());
    }

    @Test
    void 停用_管理者通過方法層級() throws Exception {
        mockMvc.perform(post("/api/admin/trainers/7/suspend")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        Mockito.verify(adminTrainerService).suspend(7);
    }

    // === POST /api/admin/trainers/{trainerId}/restore ===================================

    @Test
    void 恢復_一般員工被方法層級擋下() throws Exception {
        mockMvc.perform(post("/api/admin/trainers/7/restore")
                        .header("Authorization", "Bearer " + employeeToken()))
                .andExpect(status().isForbidden());

        Mockito.verify(adminTrainerService, Mockito.never()).restore(any());
    }

    @Test
    void 恢復_管理者通過方法層級() throws Exception {
        mockMvc.perform(post("/api/admin/trainers/7/restore")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        Mockito.verify(adminTrainerService).restore(7);
    }

    @Test
    void 恢復_未登入回401() throws Exception {
        mockMvc.perform(post("/api/admin/trainers/7/restore"))
                .andExpect(status().isUnauthorized());
    }
}
