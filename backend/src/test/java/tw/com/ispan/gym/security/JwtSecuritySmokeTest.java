package tw.com.ispan.gym.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.mockito.Mockito;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 驗證 JwtUtil / JwtAuthFilter / SecurityConfig 三者串起來的行為。
 * 不連資料庫，repository 一律以 mock 取代。
 */
@SpringBootTest(
        classes = {JwtSecuritySmokeTest.TestApp.class},
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
class JwtSecuritySmokeTest {

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
        AccountAuthorityResolver.class,
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
        TrainerRepository trainerRepository() {
            return Mockito.mock(TrainerRepository.class);
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/orders")
        String secured() {
            return "ok";
        }

        @PostMapping("/api/members/register")
        String register() {
            return "ok";
        }

        @GetMapping("/api/accounts/verify-email")
        String verifyEmail() {
            return "ok";
        }

        @PreAuthorize("hasRole('EMPLOYEE')")
        @GetMapping("/api/probe/employee")
        String employeeProbe() {
            return "ok";
        }

        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/api/probe/admin")
        String adminProbe() {
            return "ok";
        }

        @PreAuthorize("hasRole('TRAINER')")
        @GetMapping("/api/probe/trainer")
        String trainerProbe() {
            return "ok";
        }

        // 不加方法層級標註——這條要驗證的是 SecurityConfig 自己的 URL matcher
        // （/api/employees/reviews/** → hasRole(EMPLOYEE)），比照 /api/orders 的寫法
        @GetMapping("/api/employees/reviews/probe")
        String employeeReviewProbe() {
            return "ok";
        }

        // 以下三條路徑同屬 /api/member 前綴，但只有前兩條該匿名開放；
        // /bookings 是會員自己的預約紀錄，必須維持需登入（見「課表可匿名瀏覽」相關測試）
        @GetMapping("/api/member/trainers/{trainerId}/schedules")
        String publicTrainerSchedules() {
            return "ok";
        }

        @GetMapping("/api/member/group-classes")
        String publicGroupClasses() {
            return "ok";
        }

        @GetMapping("/api/member/bookings")
        String myBookings() {
            return "ok";
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired AccountRepository accounts;
    @Autowired MemberRepository members;
    @Autowired EmployeeRepository employees;
    @Autowired TrainerRepository trainers;

    private Account memberAccount() {
        Account account = new Account();
        account.setId(7);
        account.setEmail("member@example.com");
        account.setPasswordHash("{noop}irrelevant");
        account.getRoles().add(AccountType.MEMBER);
        return account;
    }

    private Member activeMember(Account account) {
        Member member = new Member();
        member.setAccount(account);
        member.setId(70); // member_id 與 account_id 已脫鉤，刻意不同號
        member.setStatus(UserStatus.ACTIVE);
        return member;
    }

    private Account employeeAccount() {
        Account account = new Account();
        account.setId(8);
        account.setEmail("employee@example.com");
        account.setPasswordHash("{noop}irrelevant");
        account.getRoles().add(AccountType.EMPLOYEE);
        return account;
    }

    private Employee activeEmployee(Account account, EmployeeRole role) {
        Employee employee = new Employee();
        employee.setAccount(account);
        employee.setId(80); // employee_id 與 account_id 已脫鉤，刻意不同號
        employee.setRole(role);
        employee.setStatus(EmployeeStatus.ACTIVE);
        return employee;
    }

    private Trainer trainer(TrainerStatus status, boolean deleted) {
        Trainer trainer = new Trainer();
        trainer.setTrainerId(800);
        trainer.setStatus(status);
        if (deleted) {
            trainer.setDeletedAt(java.time.LocalDateTime.now());
        }
        return trainer;
    }

    @Test
    void 無token時受保護端點回401() throws Exception {
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void 註冊與Email驗證端點不需登入() throws Exception {
        // 兩者都是未登入者才會用到：註冊時還沒有帳號，
        // 驗證連結則是從信件點進來的。漏開會讓整條註冊流程鎖死。
        mockMvc.perform(post("/api/members/register")).andExpect(status().isOk());
        mockMvc.perform(get("/api/accounts/verify-email")).andExpect(status().isOk());
    }

    @Test
    void 課表瀏覽不需登入() throws Exception {
        // 教練可預約時段與團課場次都只是公開排班資訊，未登入者要看得到才選得了課；
        // 這兩條路徑一度只有 controller 註解說「應該公開」，SecurityConfig 卻沒補規則，
        // 實際上被 anyRequest().authenticated() 擋成 401。
        mockMvc.perform(get("/api/member/trainers/5/schedules")).andExpect(status().isOk());
        mockMvc.perform(get("/api/member/group-classes")).andExpect(status().isOk());
    }

    @Test
    void 開放課表瀏覽不會連帶把個人預約紀錄也曝光() throws Exception {
        // /api/member/bookings 與上面兩條同前綴，若當初圖方便寫成 /api/member/**
        // 就會把會員自己的預約紀錄一起放行成匿名可讀
        mockMvc.perform(get("/api/member/bookings")).andExpect(status().isUnauthorized());
    }

    @Test
    void 帶有效token時放行並解析出帳號() throws Exception {
        Account account = memberAccount();
        Mockito.when(accounts.findById(7)).thenReturn(Optional.of(account));
        Mockito.when(members.findByAccountId(7)).thenReturn(Optional.of(activeMember(account)));

        String token = jwtUtil.generateToken(account);
        assertThat(jwtUtil.extractAccountId(token)).isEqualTo(7);
        assertThat(jwtUtil.extractEmail(token)).isEqualTo("member@example.com");

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void 帳號同時具備會員與員工身分時兩種權限並存() throws Exception {
        // Account 架構的核心優勢：員工也能以會員身分消費，共用同一組登入憑證
        Account account = memberAccount();
        account.getRoles().add(AccountType.EMPLOYEE);

        Employee employee = new Employee();
        employee.setAccount(account);
        employee.setId(71); // employee_id 與 account_id 已脫鉤，刻意不同號
        employee.setRole(EmployeeRole.ADMIN);
        employee.setStatus(EmployeeStatus.ACTIVE);

        Mockito.when(accounts.findById(7)).thenReturn(Optional.of(account));
        Mockito.when(members.findByAccountId(7)).thenReturn(Optional.of(activeMember(account)));
        Mockito.when(employees.findByAccountId(7)).thenReturn(Optional.of(employee));

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + jwtUtil.generateToken(account)))
                .andExpect(status().isOk());
    }

    @Test
    void 純EMPLOYEE角色能進employee探測點但進不去admin() throws Exception {
        Account account = employeeAccount();
        Employee employee = activeEmployee(account, EmployeeRole.EMPLOYEE);
        Mockito.when(accounts.findById(8)).thenReturn(Optional.of(account));
        Mockito.when(employees.findByAccountId(8)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(80)).thenReturn(Optional.empty());

        String token = jwtUtil.generateToken(account);
        mockMvc.perform(get("/api/probe/employee").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/probe/admin").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void ADMIN角色兩個探測點都進得去() throws Exception {
        Account account = employeeAccount();
        Employee employee = activeEmployee(account, EmployeeRole.ADMIN);
        Mockito.when(accounts.findById(8)).thenReturn(Optional.of(account));
        Mockito.when(employees.findByAccountId(8)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(80)).thenReturn(Optional.empty());

        String token = jwtUtil.generateToken(account);
        mockMvc.perform(get("/api/probe/employee").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/probe/admin").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void ADMIN且同時有教練資料時三種權限並存() throws Exception {
        Account account = employeeAccount();
        Employee employee = activeEmployee(account, EmployeeRole.ADMIN);
        Mockito.when(accounts.findById(8)).thenReturn(Optional.of(account));
        Mockito.when(employees.findByAccountId(8)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(80)).thenReturn(Optional.of(trainer(TrainerStatus.ACTIVE, false)));

        String token = jwtUtil.generateToken(account);
        mockMvc.perform(get("/api/probe/employee").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/probe/admin").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/probe/trainer").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void 沒有教練資料列進不了trainer探測點() throws Exception {
        Account account = employeeAccount();
        Employee employee = activeEmployee(account, EmployeeRole.EMPLOYEE);
        Mockito.when(accounts.findById(8)).thenReturn(Optional.of(account));
        Mockito.when(employees.findByAccountId(8)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(80)).thenReturn(Optional.empty());

        String token = jwtUtil.generateToken(account);
        mockMvc.perform(get("/api/probe/trainer").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void 教練狀態UNAVAILABLE仍保留ROLE_TRAINER() throws Exception {
        // Trainer.status 只影響能否被預約，不影響教練身分本身——deletedAt 才是身分的開關
        Account account = employeeAccount();
        Employee employee = activeEmployee(account, EmployeeRole.EMPLOYEE);
        Mockito.when(accounts.findById(8)).thenReturn(Optional.of(account));
        Mockito.when(employees.findByAccountId(8)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(80))
                .thenReturn(Optional.of(trainer(TrainerStatus.UNAVAILABLE, false)));

        String token = jwtUtil.generateToken(account);
        mockMvc.perform(get("/api/probe/trainer").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void 教練資料已軟刪除時進不了trainer探測點() throws Exception {
        Account account = employeeAccount();
        Employee employee = activeEmployee(account, EmployeeRole.EMPLOYEE);
        Mockito.when(accounts.findById(8)).thenReturn(Optional.of(account));
        Mockito.when(employees.findByAccountId(8)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(80)).thenReturn(Optional.of(trainer(TrainerStatus.ACTIVE, true)));

        String token = jwtUtil.generateToken(account);
        mockMvc.perform(get("/api/probe/trainer").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void 員工審核路徑與api_admin互不相通() throws Exception {
        // 純 EMPLOYEE 帳號能進 /api/employees/reviews/**，純 MEMBER 帳號進不去
        Account employeeAccount = employeeAccount();
        Employee employee = activeEmployee(employeeAccount, EmployeeRole.EMPLOYEE);
        Mockito.when(accounts.findById(8)).thenReturn(Optional.of(employeeAccount));
        Mockito.when(employees.findByAccountId(8)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(80)).thenReturn(Optional.empty());
        String employeeToken = jwtUtil.generateToken(employeeAccount);
        mockMvc.perform(get("/api/employees/reviews/probe").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk());

        Account memberOnlyAccount = memberAccount();
        Mockito.when(accounts.findById(7)).thenReturn(Optional.of(memberOnlyAccount));
        Mockito.when(members.findByAccountId(7)).thenReturn(Optional.of(activeMember(memberOnlyAccount)));
        String memberToken = jwtUtil.generateToken(memberOnlyAccount);
        mockMvc.perform(get("/api/employees/reviews/probe").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void 帳號被軟刪除時即使token有效也回401() throws Exception {
        Account account = memberAccount();
        String token = jwtUtil.generateToken(account);

        account.softDelete();
        Mockito.when(accounts.findById(7)).thenReturn(Optional.of(account));

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void token被竄改時回401() throws Exception {
        String token = jwtUtil.generateToken(memberAccount());
        // 直接改掉 payload 段第一個字元，簽章便對不上
        int firstDot = token.indexOf('.');
        char[] chars = token.toCharArray();
        chars[firstDot + 1] = chars[firstDot + 1] == 'e' ? 'f' : 'e';
        String tampered = new String(chars);

        assertThat(jwtUtil.isValid(tampered)).isFalse();
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 會員被停權時即使token有效也回401() throws Exception {
        Account account = memberAccount();
        String token = jwtUtil.generateToken(account);

        Member suspended = activeMember(account);
        suspended.setStatus(UserStatus.SUSPENDED);
        Mockito.when(accounts.findById(7)).thenReturn(Optional.of(account));
        Mockito.when(members.findByAccountId(7)).thenReturn(Optional.of(suspended));

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
