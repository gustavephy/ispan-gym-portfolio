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
import tw.com.ispan.gym.service.employee.EmployeeReviewService;
import tw.com.ispan.gym.service.order.OrderQueryService;
import tw.com.ispan.gym.service.order.OrderService;
import tw.com.ispan.gym.service.payment.EcpayService;
import tw.com.ispan.gym.service.product.ProductService;
import tw.com.ispan.gym.service.report.OperationReportService;

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
        "app.jwt.access-token-ttl=PT2H"
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
        tw.com.ispan.gym.controller.employee.EmployeeReviewController.class,
        tw.com.ispan.gym.controller.coupon.EmployeeCouponController.class,
        tw.com.ispan.gym.controller.product.ProductController.class,
        tw.com.ispan.gym.controller.cart.CartController.class,
        tw.com.ispan.gym.controller.order.OrderController.class,
        tw.com.ispan.gym.controller.order.AdminOrderController.class,
        tw.com.ispan.gym.controller.payment.PaymentController.class,
        tw.com.ispan.gym.controller.report.OperationReportController.class,
        // 安全基礎設施
        JwtUtil.class,
        JwtAuthFilter.class,
        AccountAuthorityResolver.class,
        UnconfiguredCurrentUserProvider.class,
        ApiErrorWriter.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        tw.com.ispan.gym.config.SecurityConfig.class,
        tw.com.ispan.gym.exception.GlobalExceptionHandler.class
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
        @Bean @Primary EmployeeCouponService employeeCouponService() {
            return Mockito.mock(EmployeeCouponService.class);
        }
        @Bean @Primary ProductService productService() {
            return Mockito.mock(ProductService.class);
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
        @Bean @Primary EcpayService ecpayService() {
            return Mockito.mock(EcpayService.class);
        }
        @Bean @Primary OperationReportService operationReportService() {
            return Mockito.mock(OperationReportService.class);
        }
        @Bean @Primary OrderRepository orderRepository() {
            return Mockito.mock(OrderRepository.class);
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
              "roomName": "A教室",
              "version": 0,
              "checkInKey": "KEY123",
              "reviewNote": "OK",
              "reason": "不符資格",
              "memberIds": [1]
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
    void 優惠券管理需員工身分() throws Exception {
        expectUnauthorizedWhenAnonymous(get("/api/employee/coupons"));
        expectForbidden(get("/api/employee/coupons"), memberToken());
        expectAuthorized(get("/api/employee/coupons"), employeeToken());
    }

    @Test
    void 群發優惠券需員工身分() throws Exception {
        expectForbidden(post("/api/employee/coupons/1/distribute-all"), memberToken());
        expectAuthorized(post("/api/employee/coupons/1/distribute-all"), employeeToken());
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
}
