package tw.com.ispan.gym.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;
import tw.com.ispan.gym.service.cart.CartService;
import tw.com.ispan.gym.service.coach.CoachBookingService;
import tw.com.ispan.gym.service.coach.CoachSlotService;
import tw.com.ispan.gym.service.coach.GroupClassService;
import tw.com.ispan.gym.service.coupon.EmployeeCouponService;
import tw.com.ispan.gym.service.employee.AdminEmployeeService;
import tw.com.ispan.gym.service.employee.EmployeeProfileService;
import tw.com.ispan.gym.service.employee.EmployeeReviewService;
import tw.com.ispan.gym.service.idempotency.IdempotencyService;
import tw.com.ispan.gym.service.order.AdminOrderQueryService;
import tw.com.ispan.gym.service.order.OrderQueryService;
import tw.com.ispan.gym.service.order.OrderService;
import tw.com.ispan.gym.service.payment.EcpayService;
import tw.com.ispan.gym.service.member.AdminMemberService;
import tw.com.ispan.gym.service.member.MemberProfileService;
import tw.com.ispan.gym.service.course.PrivateCourseService;
import tw.com.ispan.gym.service.product.ProductService;
import tw.com.ispan.gym.service.trainer.AdminTrainerService;
import tw.com.ispan.gym.service.trainer.TrainerProfileService;
import tw.com.ispan.gym.service.trainer.TrainerService;
import tw.com.ispan.gym.service.report.OperationReportService;
import tw.com.ispan.gym.service.report.ReportPdfService;
import tw.com.ispan.gym.service.account.AccountService;
import tw.com.ispan.gym.service.account.PasswordService;

/**
 * 用「真實的 controller」驗證方法層級的 @PreAuthorize 是否確實生效。
 *
 * <p>與 {@link JwtSecuritySmokeTest} 的分工：那支用自製的 ProbeController，驗的是
 * SecurityConfig 的 URL matcher 規則；這支載入正牌 controller，驗的是掛在各個方法上的
 * @PreAuthorize——兩者是不同的關卡，URL 規則放行後仍可能被方法層級擋下，反之亦然。
 * 先前沒有任何測試涵蓋後者，等於 28 個 @PreAuthorize 標註全靠人工確認。
 *
 * <p>service 一律 mock，只驗授權不驗業務邏輯；因此「通過授權」的預期結果是
 * 2xx 或 4xx 的業務錯誤，重點在於「不是 401／403」。斷言故意只看這件事。
 *
 * <p>@Profile 覆寫成 test：application.properties 硬寫 dev，不覆寫的話
 * DevSecurityConfig 全放行會讓所有斷言失去意義（比照既有三支安全測試的做法）。
 */
@SpringBootTest(
        classes = {EndpointAuthorizationTest.TestApp.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
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
class EndpointAuthorizationTest {

    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(
            exclude = {
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class
            })
    @org.springframework.context.annotation.Configuration
    @Import({
        MockedBeans.class,
        // 受測的正牌 controller
        tw.com.ispan.gym.controller.coach.AdminGroupClassController.class,
        tw.com.ispan.gym.controller.coach.CoachSlotController.class,
        tw.com.ispan.gym.controller.coach.GroupClassController.class,
        tw.com.ispan.gym.controller.coach.CoachBookingController.class,
        tw.com.ispan.gym.controller.employee.EmployeeReviewController.class,
        tw.com.ispan.gym.controller.employee.AdminEmployeeController.class,
        tw.com.ispan.gym.controller.employee.EmployeeProfileController.class,
        tw.com.ispan.gym.controller.coupon.AdminCouponController.class,
        tw.com.ispan.gym.controller.member.AdminMemberController.class,
        tw.com.ispan.gym.controller.member.MemberProfileController.class,
        tw.com.ispan.gym.controller.product.ProductController.class,
        tw.com.ispan.gym.controller.trainer.TrainerController.class,
        tw.com.ispan.gym.controller.trainer.TrainerProfileController.class,
        tw.com.ispan.gym.controller.trainer.AdminTrainerController.class,
        tw.com.ispan.gym.controller.course.AdminPrivateCourseController.class,
        tw.com.ispan.gym.controller.cart.CartController.class,
        tw.com.ispan.gym.controller.order.OrderController.class,
        tw.com.ispan.gym.controller.order.AdminOrderController.class,
        tw.com.ispan.gym.controller.payment.PaymentController.class,
        tw.com.ispan.gym.controller.payment.AdminPaymentController.class,
        tw.com.ispan.gym.controller.report.OperationReportController.class,
        tw.com.ispan.gym.controller.account.AccountController.class,
        tw.com.ispan.gym.controller.account.PasswordController.class,
        tw.com.ispan.gym.controller.account.PasswordResetController.class,
        tw.com.ispan.gym.controller.account.OAuthConnectionController.class,
        // 安全基礎設施
        JwtUtil.class,
        JwtAuthFilter.class,
        AccountAuthorityResolver.class,
        UnconfiguredCurrentUserProvider.class,
        ApiErrorWriter.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        tw.com.ispan.gym.config.SecurityConfig.class,
        tw.com.ispan.gym.exception.GlobalExceptionHandler.class,
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
        tw.com.ispan.gym.security.oauth.GoogleOAuthFailureHandler.class,
        tw.com.ispan.gym.security.AccountLoginPolicy.class
    })
    static class TestApp {}

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
        @Bean @Primary tw.com.ispan.gym.repository.account.OAuthTransactionRepository oauthTransactionRepository() {
            return Mockito.mock(tw.com.ispan.gym.repository.account.OAuthTransactionRepository.class);
        }
        @Bean @Primary tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository oauthRegistrationTicketRepository() {
            return Mockito.mock(tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository.class);
        }
        @Bean @Primary tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository oauthExchangeCodeRepository() {
            return Mockito.mock(tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository.class);
        }
        @Bean @Primary GroupClassService groupClassService() {
            return Mockito.mock(GroupClassService.class);
        }
        @Bean @Primary CoachSlotService coachSlotService() {
            return Mockito.mock(CoachSlotService.class);
        }
        @Bean @Primary CoachBookingService coachBookingService() {
            return Mockito.mock(CoachBookingService.class);
        }
        @Bean @Primary EmployeeReviewService employeeReviewService() {
            return Mockito.mock(EmployeeReviewService.class);
        }
        @Bean @Primary AdminEmployeeService adminEmployeeService() {
            return Mockito.mock(AdminEmployeeService.class);
        }
        @Bean @Primary EmployeeProfileService employeeProfileService() {
            return Mockito.mock(EmployeeProfileService.class);
        }
        @Bean @Primary MemberProfileService memberProfileService() {
            return Mockito.mock(MemberProfileService.class);
        }
        @Bean @Primary AdminMemberService adminMemberService() {
            return Mockito.mock(AdminMemberService.class);
        }
        @Bean @Primary EmployeeCouponService employeeCouponService() {
            return Mockito.mock(EmployeeCouponService.class);
        }
        @Bean @Primary ProductService productService() {
            return Mockito.mock(ProductService.class);
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
        @Bean @Primary PrivateCourseService privateCourseService() {
            return Mockito.mock(PrivateCourseService.class);
        }
        @Bean @Primary CartService cartService() {
            return Mockito.mock(CartService.class);
        }
        @Bean @Primary OrderService orderService() {
            return Mockito.mock(OrderService.class);
        }
        @Bean @Primary OrderQueryService orderQueryService() {
            return Mockito.mock(OrderQueryService.class);
        }
        @Bean @Primary AdminOrderQueryService adminOrderQueryService() {
            return Mockito.mock(AdminOrderQueryService.class);
        }
        @Bean @Primary IdempotencyService idempotencyService() {
            return Mockito.mock(IdempotencyService.class);
        }
        @Bean @Primary EcpayService ecpayService() {
            return Mockito.mock(EcpayService.class);
        }
        @Bean @Primary OperationReportService operationReportService() {
            return Mockito.mock(OperationReportService.class);
        }
        // dev 後來為報表加了 PDF 匯出，OperationReportController 因此多一個依賴；
        // 分支寫這支測試時還沒有這個 service，合併後要一併補上才組得起 context
        @Bean @Primary ReportPdfService reportPdfService() {
            return Mockito.mock(ReportPdfService.class);
        }
        @Bean @Primary tw.com.ispan.gym.service.report.ReportCsvService reportCsvService() {
            return Mockito.mock(tw.com.ispan.gym.service.report.ReportCsvService.class);
        }
        @Bean @Primary tw.com.ispan.gym.service.payment.AdminPaymentQueryService adminPaymentQueryService() {
            return Mockito.mock(tw.com.ispan.gym.service.payment.AdminPaymentQueryService.class);
        }
        @Bean @Primary OrderRepository orderRepository() {
            return Mockito.mock(OrderRepository.class);
        }
        @Bean @Primary AccountService accountService() {
            return Mockito.mock(AccountService.class);
        }
        @Bean @Primary PasswordService passwordService() {
            return Mockito.mock(PasswordService.class);
        }
        @Bean @Primary tw.com.ispan.gym.service.account.oauth.GoogleOAuthConnectionsService oauthConnectionsService() {
            return Mockito.mock(tw.com.ispan.gym.service.account.oauth.GoogleOAuthConnectionsService.class);
        }
        @Bean @Primary tw.com.ispan.gym.service.account.oauth.GoogleOAuthLinkInitiationService oauthLinkInitiationService() {
            return Mockito.mock(tw.com.ispan.gym.service.account.oauth.GoogleOAuthLinkInitiationService.class);
        }
        @Bean @Primary tw.com.ispan.gym.service.account.oauth.GoogleOAuthUnlinkService oauthUnlinkService() {
            return Mockito.mock(tw.com.ispan.gym.service.account.oauth.GoogleOAuthUnlinkService.class);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired AccountRepository accounts;
    @Autowired MemberRepository members;
    @Autowired EmployeeRepository employees;
    @Autowired TrainerRepository trainers;

    // === 各種身分的 token ======================================================

    private Account account(int id, String email, AccountType... roles) {
        Account account = new Account();
        account.setId(id);
        account.setEmail(email);
        for (AccountType role : roles) {
            account.getRoles().add(role);
        }
        // findById 供 JwtAuthFilter 展開權限；findByEmailIgnoreCase 供 controller 內部的
        // CurrentUserProvider 反查身分——少了後者，授權雖然通過，controller 仍會因為
        // 解析不出登入者而回 401，看起來像授權失敗
        Mockito.when(accounts.findById(id)).thenReturn(Optional.of(account));
        Mockito.when(accounts.findByEmailIgnoreCase(email)).thenReturn(Optional.of(account));
        return account;
    }

    /** 純會員 */
    private String memberToken() {
        Account account = account(1, "member@example.com", AccountType.MEMBER);
        Member member = new Member();
        member.setId(11);
        member.setAccount(account);
        member.setStatus(UserStatus.ACTIVE);
        Mockito.when(members.findByAccountId(1)).thenReturn(Optional.of(member));
        return jwtUtil.generateToken(account);
    }

    /**
     * 純 SUSPENDED 會員，帳號沒有其他啟用中身分：AccountAuthorityResolver 回空權限清單，
     * JwtAuthFilter 會直接當匿名處理，完全不寫入 SecurityContext——這個帳號打任何
     * authenticated() 端點都是 401，不是方法層級的 403。
     */
    private String suspendedMemberToken() {
        Account account = account(5, "suspended@example.com", AccountType.MEMBER);
        Member member = new Member();
        member.setId(55);
        member.setAccount(account);
        member.setStatus(UserStatus.SUSPENDED);
        Mockito.when(members.findByAccountId(5)).thenReturn(Optional.of(member));
        return jwtUtil.generateToken(account);
    }

    /**
     * 同一帳號兼具 SUSPENDED 會員與 ACTIVE 員工：員工身分本身就讓 AccountAuthorityResolver
     * 回傳非空權限清單（ROLE_EMPLOYEE），與 Member 是否 SUSPENDED 無關，所以這個帳號能通過
     * JwtAuthFilter 的認證關卡，不會被當成匿名。
     */
    private String suspendedMemberWithActiveEmployeeToken() {
        Account account = account(6, "dual@example.com", AccountType.MEMBER, AccountType.EMPLOYEE);

        Member member = new Member();
        member.setId(66);
        member.setAccount(account);
        member.setStatus(UserStatus.SUSPENDED);
        Mockito.when(members.findByAccountId(6)).thenReturn(Optional.of(member));

        Employee employee = new Employee();
        employee.setId(266);
        employee.setAccount(account);
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(EmployeeStatus.ACTIVE);
        Mockito.when(employees.findByAccountId(6)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(266)).thenReturn(Optional.empty());

        return jwtUtil.generateToken(account);
    }

    /** 一般員工（非管理者、非教練） */
    private String employeeToken() {
        Account account = account(2, "employee@example.com", AccountType.EMPLOYEE);
        Employee employee = new Employee();
        employee.setId(22);
        employee.setAccount(account);
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(EmployeeStatus.ACTIVE);
        Mockito.when(employees.findByAccountId(2)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(22)).thenReturn(Optional.empty());
        return jwtUtil.generateToken(account);
    }

    /** 管理者 */
    private String adminToken() {
        Account account = account(3, "admin@example.com", AccountType.EMPLOYEE);
        Employee employee = new Employee();
        employee.setId(33);
        employee.setAccount(account);
        employee.setRole(EmployeeRole.ADMIN);
        employee.setStatus(EmployeeStatus.ACTIVE);
        Mockito.when(employees.findByAccountId(3)).thenReturn(Optional.of(employee));
        Mockito.when(trainers.findByEmployeeId(33)).thenReturn(Optional.empty());
        return jwtUtil.generateToken(account);
    }

    /**
     * 純 INACTIVE 員工，帳號沒有其他啟用中身分：AccountAuthorityResolver 回空權限清單，
     * JwtAuthFilter 會直接當匿名處理，完全不寫入 SecurityContext——這個帳號打
     * /api/employee/profile 是 401，不是方法層級的 403，跟 suspendedMemberToken() 是同一種情境。
     */
    private String inactiveEmployeeToken() {
        Account account = account(7, "inactive-employee@example.com", AccountType.EMPLOYEE);
        Employee employee = new Employee();
        employee.setId(77);
        employee.setAccount(account);
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(EmployeeStatus.INACTIVE);
        Mockito.when(employees.findByAccountId(7)).thenReturn(Optional.of(employee));
        return jwtUtil.generateToken(account);
    }

    /**
     * 同一帳號兼具 ACTIVE 會員與 INACTIVE 員工：會員身分本身就讓 AccountAuthorityResolver
     * 回傳非空權限清單（ROLE_MEMBER），帳號因此能通過 JwtAuthFilter 的認證關卡，不會被當成
     * 匿名；但員工身分是 INACTIVE，拿不到 ROLE_EMPLOYEE，員工自助端點仍應被方法層級的
     * hasRole('EMPLOYEE') 擋下——這正是 EmployeeProfileController 選 hasRole('EMPLOYEE') 而不是
     * 比照 Member 用 .authenticated() 的理由：只要求已登入會讓這種雙身分帳號繞過 INACTIVE 限制。
     */
    private String activeMemberWithInactiveEmployeeToken() {
        Account account = account(8, "dual-inactive-employee@example.com", AccountType.MEMBER, AccountType.EMPLOYEE);

        Member member = new Member();
        member.setId(88);
        member.setAccount(account);
        member.setStatus(UserStatus.ACTIVE);
        Mockito.when(members.findByAccountId(8)).thenReturn(Optional.of(member));

        Employee employee = new Employee();
        employee.setId(288);
        employee.setAccount(account);
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(EmployeeStatus.INACTIVE);
        Mockito.when(employees.findByAccountId(8)).thenReturn(Optional.of(employee));

        return jwtUtil.generateToken(account);
    }

    /** 教練＝有 trainer 資料列的員工（身分由 trainer 表決定，與 role 無關） */
    private String trainerToken() {
        Account account = account(4, "trainer@example.com", AccountType.EMPLOYEE);
        Employee employee = new Employee();
        employee.setId(44);
        employee.setAccount(account);
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(EmployeeStatus.ACTIVE);
        Mockito.when(employees.findByAccountId(4)).thenReturn(Optional.of(employee));
        Trainer trainer = new Trainer();
        trainer.setEmployee(employee);
        // 教練身分只看 trainer 資料列是否存在且未軟刪除，與 status 無關（見 AccountAuthorityResolver）
        trainer.setStatus(TrainerStatus.ACTIVE);
        Mockito.when(trainers.findByEmployeeId(44)).thenReturn(Optional.of(trainer));
        return jwtUtil.generateToken(account);
    }

    // === 斷言輔助 =============================================================

    /**
     * 預設送出的 body。
     *
     * <p>刻意送「通得過驗證」的內容：@Valid @RequestBody 的參數解析發生在方法層級
     * @PreAuthorize 之前，body 不合法會先回 400，授權結果就看不到了
     * （/api/admin/** 例外——它在 SecurityConfig 有 URL 層規則，filter 階段就擋掉，
     * 不受 body 影響）。這個 body 對本測試用到的各種請求都足以通過欄位驗證。
     */
    private static final String VALID_BODY = """
            {
              "startAt": "2030-01-01T10:00:00",
              "endAt": "2030-01-01T11:00:00",
              "groupCourseId": 1,
              "roomName": "YOGA_ROOM",
              "version": 0,
              "checkInKey": "KEY123",
              "reviewNote": "OK",
              "reason": "不符資格",
              "memberIds": [1],
              "specialty": "重量訓練",
              "experience": "五年授課經驗",
              "bio": "自我介紹",
              "firstName": "小美",
              "lastName": "王",
              "phone": "0912345678",
              "hireDate": "2020-01-01",
              "role": "ADMIN",
              "status": "ACTIVE",
              "profileImageUrl": "https://example.com/trainer/7.jpg"
            }
            """;

    /** 未登入必須是 401（而不是被方法層級擋成 403，或更糟：直接放行） */
    private void expectUnauthorizedWhenAnonymous(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    /** 角色不符必須是 403 */
    private void expectForbidden(MockHttpServletRequestBuilder request, String token) throws Exception {
        mockMvc.perform(request.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
    }

    /**
     * 角色相符就不該被安全層擋下。service 全是 mock，後續可能因為缺資料回 4xx/5xx 業務錯誤，
     * 這裡只確認「不是 401／403」——授權有沒有過，才是這支測試要驗的事。
     */
    private void expectAuthorized(MockHttpServletRequestBuilder request, String token) throws Exception {
        int status = mockMvc.perform(request.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status)
                .as("授權應通過，不該是 401／403（實際 %d）", status)
                .isNotIn(401, 403);
    }

    // === ADMIN：團課場次管理 ===================================================

    @Test
    void 建立團課場次僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(post("/api/admin/group-classes"));
        expectForbidden(post("/api/admin/group-classes"), memberToken());
        // 一般員工也不行——/api/admin/** 是管理者專屬
        expectForbidden(post("/api/admin/group-classes"), employeeToken());
        expectAuthorized(post("/api/admin/group-classes"), adminToken());
    }

    @Test
    void 更新與取消團課場次僅限管理者() throws Exception {
        expectForbidden(put("/api/admin/group-classes/1"), employeeToken());
        expectForbidden(delete("/api/admin/group-classes/1"), employeeToken());
        expectAuthorized(delete("/api/admin/group-classes/1"), adminToken());
    }

    // === ADMIN：商品維護 =======================================================

    @Test
    void 商品維護僅限管理者但瀏覽開放所有人() throws Exception {
        expectForbidden(put("/api/admin/products/1"), memberToken());
        expectForbidden(delete("/api/admin/products/1"), employeeToken());
        expectAuthorized(put("/api/admin/products/1"), adminToken());

        // 前台瀏覽不需登入（SecurityConfig 已 permitAll）
        mockMvc.perform(get("/api/products")).andExpect(status().isOk());
    }

    // === EMPLOYEE：員工審核與優惠券 =============================================

    @Test
    void 員工審核需在職員工身分() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/employees/reviews/pending"));
        expectForbidden(get("/api/employees/reviews/pending"), memberToken());
        expectAuthorized(get("/api/employees/reviews/pending"), employeeToken());
        // 管理者本身也是員工，同樣可以審核
        expectAuthorized(get("/api/employees/reviews/pending"), adminToken());
    }

    @Test
    void 核准與拒絕員工申請需員工身分() throws Exception {
        expectForbidden(post("/api/employees/reviews/9/approve"), memberToken());
        expectForbidden(post("/api/employees/reviews/9/reject"), memberToken());
    }

    @Test
    void 優惠券管理需管理員身分() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/coupons"));
        expectForbidden(get("/api/admin/coupons"), memberToken());
        expectForbidden(get("/api/admin/coupons"), employeeToken());
        expectAuthorized(get("/api/admin/coupons"), adminToken());
    }

    @Test
    void 群發優惠券需管理員身分() throws Exception {
        expectForbidden(post("/api/admin/coupons/1/distribute-all"), memberToken());
        expectForbidden(post("/api/admin/coupons/1/distribute-all"), employeeToken());
        expectAuthorized(post("/api/admin/coupons/1/distribute-all"), adminToken());
    }

    // === TRAINER：教練排班 =====================================================

    @Test
    void 教練排班需教練身分而非只是員工() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/trainer/schedules"));
        expectForbidden(get("/api/trainer/schedules"), memberToken());
        // 關鍵：一般員工沒有 trainer 資料列，拿不到 ROLE_TRAINER
        expectForbidden(get("/api/trainer/schedules"), employeeToken());
        expectAuthorized(get("/api/trainer/schedules"), trainerToken());
    }

    @Test
    void 新增與修改時段需教練身分() throws Exception {
        expectForbidden(post("/api/trainer/schedules"), employeeToken());
        expectForbidden(put("/api/trainer/schedules/1"), memberToken());
        expectAuthorized(post("/api/trainer/schedules"), trainerToken());
    }

    @Test
    void 課程核銷需教練身分() throws Exception {
        expectForbidden(put("/api/trainer/schedules/1/checkin"), adminToken());
        expectAuthorized(put("/api/trainer/schedules/1/checkin"), trainerToken());
    }

    // === MEMBER：團課報名 ======================================================

    @Test
    void 團課報名需會員身分() throws Exception {
        expectUnauthorizedWhenAnonymous(post("/api/member/group-classes/1/booking"));
        // 純員工沒有會員身分，不能報名
        expectForbidden(post("/api/member/group-classes/1/booking"), employeeToken());
        expectAuthorized(post("/api/member/group-classes/1/booking"), memberToken());
    }

    @Test
    void 取消團課報名需會員身分() throws Exception {
        expectForbidden(delete("/api/member/group-classes/bookings/1"), adminToken());
        expectAuthorized(delete("/api/member/group-classes/bookings/1"), memberToken());
    }

    @Test
    void 團課場次瀏覽不需登入() throws Exception {
        // 同一支 controller 內，只有報名相關的方法掛 @PreAuthorize，列表刻意不掛
        mockMvc.perform(get("/api/member/group-classes")).andExpect(status().isOk());
    }

    // === ADMIN：員工升級為教練 ===================================================
    @Test
    void 建立教練僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(post("/api/employees/22/trainer"));
        expectForbidden(post("/api/employees/22/trainer"), memberToken());
        // 關鍵：一般在職員工可以「審核」同事，但不能把人升級成教練
        expectForbidden(post("/api/employees/22/trainer"), employeeToken());
        // 教練身分本身也不夠——升級教練是管理者的權責
        expectForbidden(post("/api/employees/22/trainer"), trainerToken());
        expectAuthorized(post("/api/employees/22/trainer"), adminToken());
    }

    // === TRAINER：教練本人專業資料 ===============================================

    @Test
    void 教練本人查詢與修改需教練身分而非只是員工() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/trainer/profile"));
        expectForbidden(get("/api/trainer/profile"), memberToken());
        // 關鍵：一般員工沒有 trainer 資料列，拿不到 ROLE_TRAINER
        expectForbidden(get("/api/trainer/profile"), employeeToken());
        expectAuthorized(get("/api/trainer/profile"), trainerToken());

        expectForbidden(put("/api/trainer/profile"), employeeToken());
        expectAuthorized(put("/api/trainer/profile"), trainerToken());
    }

    // === MEMBER：自助查詢／修改個人資料 ==========================================

    @Test
    void 查詢自己資料只需登入() throws Exception {
        mockMvc.perform(get("/api/member/profile"))
                .andExpect(status().isUnauthorized());

        expectAuthorized(get("/api/member/profile"), memberToken());
        // GET 沒有方法層級角色限制，純員工帳號一樣能通過安全層——
        // 「沒有會員資料」是業務層 403（ForbiddenAccessException），Service 已 mock 不會觸發，
        // 這裡只驗證安全層本身沒有把非會員擋下
        expectAuthorized(get("/api/member/profile"), employeeToken());
    }

    /**
     * 純 SUSPENDED 會員（帳號沒有其他啟用中身分）完全打不進安全層——不是方法層級的 403，
     * 是 JwtAuthFilter 把空權限清單的帳號當匿名處理，跟沒帶 token 是同一種 401。
     * 這是既有、刻意的設計（2026-08-15），這個功能不打算為此另外開權限口子。
     */
    @Test
    void 純停權會員完全打不進安全層() throws Exception {
        mockMvc.perform(get("/api/member/profile")
                        .header("Authorization", "Bearer " + suspendedMemberToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/member/profile")
                        .header("Authorization", "Bearer " + suspendedMemberToken())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 同帳號若還有 ACTIVE 員工身分，該身分本身就讓帳號能通過認證關卡，與 Member 是否
     * SUSPENDED 無關。GET 因此能通過安全層（實際資料由 Service 判斷），PUT 仍因缺少
     * ROLE_MEMBER 被方法層級擋下——員工功能不受 Member 停權影響。
     */
    @Test
    void 雙身分帳號的員工身分讓GET能通過安全層但PUT仍被擋() throws Exception {
        expectAuthorized(get("/api/member/profile"), suspendedMemberWithActiveEmployeeToken());
        expectForbidden(put("/api/member/profile"), suspendedMemberWithActiveEmployeeToken());
    }

    @Test
    void 修改自己資料需有效會員身分() throws Exception {
        expectUnauthorizedWhenAnonymous(put("/api/member/profile"));
        expectForbidden(put("/api/member/profile"), employeeToken());
        expectAuthorized(put("/api/member/profile"), memberToken());
    }

    // === ADMIN：會員管理（僅驗 URL 層，方法層 @PreAuthorize 由 AdminMemberMethodSecurityTest 負責）====
    //
    // /api/admin/** 在正式 SecurityConfig 有 URL matcher hasRole("ADMIN")，filter 階段就會
    // 攔截非 ADMIN，AdminMemberController 方法上的 @PreAuthorize 永遠執行不到——這支測試因此
    // 只能證明「URL 層規則生效」，不能宣稱已驗證方法層 @PreAuthorize 本身是否正確（跟
    // TrainerController.create() 的情況相同，見上方「建立教練僅限管理者」與
    // TrainerMethodSecurityTest 的分工說明）。

    @Test
    void 會員列表僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/members"));
        expectForbidden(get("/api/admin/members"), memberToken());
        expectForbidden(get("/api/admin/members"), employeeToken());
        expectAuthorized(get("/api/admin/members"), adminToken());
    }

    @Test
    void 單一會員查詢與代改僅限管理者() throws Exception {
        expectForbidden(get("/api/admin/members/1"), employeeToken());
        expectForbidden(put("/api/admin/members/1"), employeeToken());
        expectAuthorized(get("/api/admin/members/1"), adminToken());
        expectAuthorized(put("/api/admin/members/1"), adminToken());
    }

    @Test
    void 停用與恢復僅限管理者() throws Exception {
        expectForbidden(post("/api/admin/members/1/suspend"), employeeToken());
        expectForbidden(post("/api/admin/members/1/activate"), memberToken());
        expectAuthorized(post("/api/admin/members/1/suspend"), adminToken());
        expectAuthorized(post("/api/admin/members/1/activate"), adminToken());
    }

    @Test
    void 查詢指定會員課程額度僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/members/1/course-quotas"));
        expectForbidden(get("/api/admin/members/1/course-quotas"), memberToken());
        expectForbidden(get("/api/admin/members/1/course-quotas"), employeeToken());
        expectAuthorized(get("/api/admin/members/1/course-quotas"), adminToken());
    }

    @Test
    void 查詢指定會員團課報名紀錄僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/members/1/group-class-bookings"));
        expectForbidden(get("/api/admin/members/1/group-class-bookings"), memberToken());
        expectForbidden(get("/api/admin/members/1/group-class-bookings"), employeeToken());
        expectAuthorized(get("/api/admin/members/1/group-class-bookings"), adminToken());
    }

    @Test
    void 查詢指定會員私教課預約歷史僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/members/1/coach-bookings"));
        expectForbidden(get("/api/admin/members/1/coach-bookings"), memberToken());
        expectForbidden(get("/api/admin/members/1/coach-bookings"), employeeToken());
        expectAuthorized(get("/api/admin/members/1/coach-bookings"), adminToken());
    }

    @Test
    void 查詢指定會員堂數異動稽核紀錄僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/members/1/deduction-logs"));
        expectForbidden(get("/api/admin/members/1/deduction-logs"), memberToken());
        expectForbidden(get("/api/admin/members/1/deduction-logs"), employeeToken());
        expectAuthorized(get("/api/admin/members/1/deduction-logs"), adminToken());
    }

    // === MEMBER：私教課額度查詢 ================================================
    //
    // memberId 一律由 CurrentUserProvider 從 token 反查（controller 不收參數），
    // 所以這裡只需驗角色關卡：非 MEMBER 一律 403，未登入 401。

    @Test
    void 查詢自己課程額度僅限會員() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/member/quotas"));
        expectForbidden(get("/api/member/quotas"), employeeToken());
        expectForbidden(get("/api/member/quotas"), adminToken());
        expectAuthorized(get("/api/member/quotas"), memberToken());
    }

    // === ADMIN：員工管理（僅驗 URL 層，方法層 @PreAuthorize 由 AdminEmployeeMethodSecurityTest 負責）====
    //
    // /api/admin/** 在正式 SecurityConfig 有 URL matcher hasRole("ADMIN")，filter 階段就會
    // 攔截非 ADMIN，AdminEmployeeController 方法上的 @PreAuthorize 永遠執行不到——這支測試因此
    // 只能證明「URL 層規則生效」，不能宣稱已驗證方法層 @PreAuthorize 本身是否正確（跟
    // 會員管理那組測試的分工說明相同）。不動既有的員工申請、同儕審核、拒絕後重新申請測試。

    @Test
    void 員工列表僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/employees"));
        expectForbidden(get("/api/admin/employees"), memberToken());
        expectForbidden(get("/api/admin/employees"), employeeToken());
        expectAuthorized(get("/api/admin/employees"), adminToken());
    }

    @Test
    void 單一員工查詢與代改僅限管理者() throws Exception {
        expectForbidden(get("/api/admin/employees/1"), employeeToken());
        expectForbidden(put("/api/admin/employees/1"), employeeToken());
        expectAuthorized(get("/api/admin/employees/1"), adminToken());
        expectAuthorized(put("/api/admin/employees/1"), adminToken());
    }

    @Test
    void 角色調整僅限管理者() throws Exception {
        expectForbidden(post("/api/admin/employees/1/role"), employeeToken());
        expectAuthorized(post("/api/admin/employees/1/role"), adminToken());
    }

    @Test
    void 停用與恢復僅限管理者_員工版() throws Exception {
        expectForbidden(post("/api/admin/employees/1/suspend"), employeeToken());
        expectForbidden(post("/api/admin/employees/1/activate"), memberToken());
        expectAuthorized(post("/api/admin/employees/1/suspend"), adminToken());
        expectAuthorized(post("/api/admin/employees/1/activate"), adminToken());
    }

    // === ADMIN：教練管理（僅驗 URL 層，方法層 @PreAuthorize 由 TrainerMethodSecurityTest 負責）====
    //
    // /api/admin/** 在正式 SecurityConfig 有 URL matcher hasRole("ADMIN")，理由與分工說明
    // 同上方「ADMIN：員工管理」區塊。教練本人（trainerToken）也不例外——教練管理是管理者的
    // 權責，不是教練自己的權責，故用 employeeToken／trainerToken 兩種身分都驗一次 forbidden。

    @Test
    void 教練列表與單筆查詢僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/trainers"));
        expectForbidden(get("/api/admin/trainers"), employeeToken());
        expectForbidden(get("/api/admin/trainers"), trainerToken());
        expectAuthorized(get("/api/admin/trainers"), adminToken());

        expectForbidden(get("/api/admin/trainers/1"), trainerToken());
        expectAuthorized(get("/api/admin/trainers/1"), adminToken());
    }

    @Test
    void 教練代改專業資料僅限管理者() throws Exception {
        expectForbidden(put("/api/admin/trainers/1"), trainerToken());
        expectAuthorized(put("/api/admin/trainers/1"), adminToken());
    }

    @Test
    void 教練狀態切換僅限管理者() throws Exception {
        expectForbidden(post("/api/admin/trainers/1/status"), trainerToken());
        expectAuthorized(post("/api/admin/trainers/1/status"), adminToken());
    }

    @Test
    void 教練停用預檢停用與恢復僅限管理者() throws Exception {
        expectForbidden(get("/api/admin/trainers/1/suspension-check"), trainerToken());
        expectForbidden(post("/api/admin/trainers/1/suspend"), trainerToken());
        expectForbidden(post("/api/admin/trainers/1/restore"), employeeToken());
        expectAuthorized(get("/api/admin/trainers/1/suspension-check"), adminToken());
        expectAuthorized(post("/api/admin/trainers/1/suspend"), adminToken());
        expectAuthorized(post("/api/admin/trainers/1/restore"), adminToken());
    }

    /**
     * 這幾個端點是 code review（2026-08-19）後新增的：教練停用前會檢查名下是否還有
     * 生效中的私教課，在此之前完全沒有辦法把 PrivateCourse 設回 INACTIVE，
     * 等於停用流程被卡死（見 trainer_management_next_phase 規格基準）。
     */
    @Test
    void 私教課下架與重新上架僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/private-courses").param("trainerId", "1"));
        expectForbidden(get("/api/admin/private-courses").param("trainerId", "1"), trainerToken());
        expectForbidden(post("/api/admin/private-courses/1/deactivate"), employeeToken());
        expectForbidden(post("/api/admin/private-courses/1/activate"), trainerToken());
        expectAuthorized(get("/api/admin/private-courses").param("trainerId", "1"), adminToken());
        expectAuthorized(post("/api/admin/private-courses/1/deactivate"), adminToken());
        expectAuthorized(post("/api/admin/private-courses/1/activate"), adminToken());
    }

    // === EMPLOYEE：自助查詢／修改個人資料 ==========================================

    @Test
    void 員工查詢與修改自己資料需在職員工身分() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/employee/profile"));
        expectForbidden(get("/api/employee/profile"), memberToken());
        expectAuthorized(get("/api/employee/profile"), employeeToken());

        expectUnauthorizedWhenAnonymous(put("/api/employee/profile"));
        expectForbidden(put("/api/employee/profile"), memberToken());
        expectAuthorized(put("/api/employee/profile"), employeeToken());
    }

    /**
     * 純 INACTIVE 員工（帳號沒有其他啟用中身分）完全打不進安全層——不是方法層級的 403，
     * 是 JwtAuthFilter 把空權限清單的帳號當匿名處理，跟沒帶 token 是同一種 401。
     * 這是這次規格刻意的設計：INACTIVE 員工停用後連查自己的資料都看不到。
     */
    @Test
    void 純INACTIVE員工完全打不進安全層() throws Exception {
        mockMvc.perform(get("/api/employee/profile")
                        .header("Authorization", "Bearer " + inactiveEmployeeToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/employee/profile")
                        .header("Authorization", "Bearer " + inactiveEmployeeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 同帳號若還有 ACTIVE 會員身分，該身分本身就讓帳號能通過認證關卡，與員工是否 INACTIVE
     * 無關——GET／PUT 因此都能通過安全層的認證，但仍因為員工身分本身缺少 ROLE_EMPLOYEE
     * 被方法層級擋下 403。這正是驗證 hasRole('EMPLOYEE') 而非 .authenticated() 的關鍵情境：
     * 若員工自助端點也比照 Member 只要求已登入，這個雙身分帳號就會繞過 INACTIVE 限制看到資料。
     */
    @Test
    void 雙身分帳號的ACTIVE會員身分讓帳號能通過認證但INACTIVE員工的profile仍被擋下() throws Exception {
        expectForbidden(get("/api/employee/profile"), activeMemberWithInactiveEmployeeToken());
        expectForbidden(put("/api/employee/profile"), activeMemberWithInactiveEmployeeToken());
    }

    @Test
    void 公開教練列表與單筆查詢不需登入() throws Exception {
        mockMvc.perform(get("/api/trainers")).andExpect(status().isOk());
        // 單筆走 service（已 mock，回 null）不影響授權判斷，重點是沒有被擋成 401
        int status = mockMvc.perform(get("/api/trainers/7")).andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status)
                .as("公開單筆查詢不該被安全層擋下（實際 %d）", status)
                .isNotIn(401, 403);
    }

    /**
     * 聊天室選教練用的 chat-directory 含 employeeId，跟公開列表不同 DTO、不同權限層級——
     * 必須登入且限會員身分，驗證沒有因為路徑掛在 /api/trainers 之下而誤被公開 matcher 放行。
     */
    @Test
    void 聊天室教練名冊需要會員登入() throws Exception {
        mockMvc.perform(get("/api/member/trainers/chat-directory"))
                .andExpect(status().isUnauthorized());
        expectForbidden(get("/api/member/trainers/chat-directory"), employeeToken());
        expectAuthorized(get("/api/member/trainers/chat-directory"), memberToken());
    }

    // === MEMBER：購物車、訂單、綠界結帳 =========================================
    // 這三支 controller 沒有掛 @PreAuthorize，權限完全靠 SecurityConfig 的
    // anyRequest().authenticated() 兜底；JwtSecuritySmokeTest 已用假的 ProbeController
    // 驗過同一條 URL 規則本身，這裡改用真牌 controller，確認規則真的套用到它們身上。

    @Test
    void 購物車需要登入() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/cart"));
        expectAuthorized(get("/api/cart"), memberToken());
    }

    @Test
    void 訂單查詢與建立需要登入() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/orders"));
        expectAuthorized(get("/api/orders"), memberToken());
        expectAuthorized(post("/api/orders"), memberToken());
    }

    @Test
    void 綠界結帳查詢需要登入但付款通知不需要() throws Exception {
        expectUnauthorizedWhenAnonymous(post("/api/payments/ecpay/checkout/1"));
        expectAuthorized(post("/api/payments/ecpay/checkout/1"), memberToken());

        // server-to-server 通知不帶登入態（PaymentController#notify），匿名必須放行；
        // 端點對任何輸入一律回 200 的「1|OK」，這裡只確認沒有被安全層擋成 401
        mockMvc.perform(post("/api/payments/ecpay/notify"))
                .andExpect(status().isOk());
    }

    // === ADMIN：後台訂單管理與營運報表 ===========================================

    @Test
    void 後台訂單與退款管理僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/orders/refunds/pending"));
        expectForbidden(get("/api/admin/orders/refunds/pending"), memberToken());
        expectForbidden(get("/api/admin/orders/refunds/pending"), employeeToken());
        expectAuthorized(get("/api/admin/orders/refunds/pending"), adminToken());
    }

    @Test
    void 營運報表僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/reports"));
        expectForbidden(get("/api/admin/reports"), memberToken());
        expectForbidden(get("/api/admin/reports"), employeeToken());
        expectAuthorized(get("/api/admin/reports"), adminToken());
    }

    @Test
    void 後台金流交易紀錄僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/admin/payments/transactions"));
        expectForbidden(get("/api/admin/payments/transactions"), memberToken());
        expectForbidden(get("/api/admin/payments/webhooks"), employeeToken());
        expectAuthorized(get("/api/admin/payments/transactions"), adminToken());
    }

    @Test
    void 模擬付款端點僅限管理者() throws Exception {
        expectUnauthorizedWhenAnonymous(post("/api/admin/orders/1/simulate-payment"));
        expectForbidden(post("/api/admin/orders/1/simulate-payment"), memberToken());
        expectForbidden(post("/api/admin/orders/1/simulate-payment"), employeeToken());
        expectAuthorized(post("/api/admin/orders/1/simulate-payment"), adminToken());
    }

    // === 密碼管理 ================================================================
    // PasswordController／PasswordResetController 都沒有 @PreAuthorize，
    // 不需要另開方法層級測試類別（見 memory/password_management_phase_scope.md）。

    /** 密碼是 Account 層級概念，不分會員／員工，任何已登入帳號都能改自己的密碼 */
    @Test
    void 修改密碼需要登入_任何已登入身分皆可() throws Exception {
        expectUnauthorizedWhenAnonymous(post("/api/account/me/change-password"));
        expectAuthorized(post("/api/account/me/change-password"), memberToken());
        expectAuthorized(post("/api/account/me/change-password"), employeeToken());
    }

    /** 忘記密碼申請／憑 Token 重設密碼都是匿名端點，落在既有 /api/auth/** permitAll 規則裡 */
    @Test
    void 忘記密碼與重設密碼不需登入() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "someone@example.com" }
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "token": "t", "newPassword": "Newpass@2026", "confirmPassword": "Newpass@2026" }
                                """))
                .andExpect(status().isNoContent());
    }

    // === Google OAuth 綁定管理 ====================================================
    // OAuthConnectionController 都沒有 @PreAuthorize，跟密碼管理一樣，
    // Account 層級概念不分會員／員工，任何已登入帳號都能操作自己的 Google 綁定。

    /** 對應必補案例「未登入不能查詢／發起／解除」 */
    @Test
    void 查詢Google綁定狀態需要登入_任何已登入身分皆可() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/account/me/oauth-connections"));
        expectAuthorized(get("/api/account/me/oauth-connections"), memberToken());
        expectAuthorized(get("/api/account/me/oauth-connections"), employeeToken());
    }

    @Test
    void 發起Google綁定需要登入_任何已登入身分皆可() throws Exception {
        expectUnauthorizedWhenAnonymous(post("/api/account/me/oauth/google/link"));
        expectAuthorized(post("/api/account/me/oauth/google/link"), memberToken());
        expectAuthorized(post("/api/account/me/oauth/google/link"), employeeToken());
    }

    @Test
    void 解除Google綁定需要登入_任何已登入身分皆可() throws Exception {
        expectUnauthorizedWhenAnonymous(delete("/api/account/me/oauth/google"));
        expectAuthorized(delete("/api/account/me/oauth/google"), memberToken());
        expectAuthorized(delete("/api/account/me/oauth/google"), employeeToken());
    }

    // AccountController 都沒有 @PreAuthorize，跟密碼管理／Google 綁定一樣，
    // 帳號層級的自助操作不分身分，只要求已登入。
    @Test
    void 查詢帳號身份快照需要登入_任何已登入身分皆可() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/account/me"));
        expectAuthorized(get("/api/account/me"), memberToken());
        expectAuthorized(get("/api/account/me"), employeeToken());
    }

    @Test
    void 全裝置登出需要登入_任何已登入身分皆可() throws Exception {
        expectUnauthorizedWhenAnonymous(post("/api/account/me/logout-all"));
        expectAuthorized(post("/api/account/me/logout-all"), memberToken());
        expectAuthorized(post("/api/account/me/logout-all"), employeeToken());
    }
}
