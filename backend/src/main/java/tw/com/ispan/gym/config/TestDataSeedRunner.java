package tw.com.ispan.gym.config;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;
import tw.com.ispan.gym.repository.product.ProductCategoryRepository;
import tw.com.ispan.gym.repository.product.ProductRepository;
import tw.com.ispan.gym.repository.subscription.SubscriptionPlanRepository;
import tw.com.ispan.gym.repository.course.CourseCategoryRepository;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;
import tw.com.ispan.gym.repository.coach.GroupClassSessionRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.payment.PaymentTransactionRepository;
import tw.com.ispan.gym.repository.member.MemberSubscriptionRepository;
import tw.com.ispan.gym.repository.coach.MemberCourseQuotaRepository;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.entity.coach.MemberCourseQuota;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.entity.product.ProductCategory;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.enums.coupon.CouponType;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;
import tw.com.ispan.gym.entity.coach.GroupClassSession;
import tw.com.ispan.gym.enums.coach.ClassRoom;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/**
 * 開發用測試帳號播種：讓每位組員的本機資料庫收斂到同一組已知帳號，
 * 前端登入頁的測試帳號面板（LoginView.vue 的 TEST_ACCOUNTS）即以此為準。
 *
 * <p>
 * 四組固定帳號（密碼一律 {@value #TEST_PASSWORD}）：
 * <ul>
 * <li>wendy-test@local.test —— 純會員</li>
 * <li>emp-admin@local.test —— 會員＋管理員（雙重身分）</li>
 * <li>admin-only@local.test —— 純管理員，無會員身分（管理端畫面較乾淨）</li>
 * <li>emp-trainer@local.test —— 會員＋員工＋教練（三重身分，適合展示角色疊加）</li>
 * </ul>
 *
 * <p>
 * 與 {@link AdminSeedRunner} 的關鍵差異：AdminSeed 遇到不一致會報錯拒絕自動修復
 * （始祖帳號屬安全敏感，不可自動升權）；本 runner 的目的正是「統一各機器的測試狀態」，
 * 因此對既有帳號採<b>自動修復</b>——密碼不符就重設、身分列缺漏就補建、狀態不對就導正，
 * 每項修復都記 log。修復範圍僅限上述固定 email，不會碰其他帳號。
 *
 * <p>
 * 冪等：每次啟動都跑，狀態已正確時不寫入任何資料。
 * {@code @Order(2)} 排在 {@link SchemaFixupRunner}（0）與
 * {@link AdminSeedRunner}（1）之後。
 * 正式環境以 {@code SEED_TEST_DATA_ENABLED=false} 停用——測試帳號密碼是公開已知的，
 * 絕不可存在於對外環境。
 */
@Component
@Order(2)
public class TestDataSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TestDataSeedRunner.class);

    /** 與 LoginView.vue 測試帳號面板一致；改這裡要同步改前端 */
    private static final String TEST_PASSWORD = "Admin@12345";

    /** 樣本訂單的 order_no 前綴，用來辨識哪些是播種產生的，不會誤動真實下單流程的訂單 */
    private static final String SAMPLE_ORDER_PREFIX = "SEED-ORD-";
    private static final int SAMPLE_ORDER_COUNT = 10;

    private final AccountRepository accounts;
    private final MemberRepository members;
    private final EmployeeRepository employees;
    private final TrainerRepository trainers;
    private final ProductCategoryRepository productCategories;
    private final ProductRepository products;
    private final SubscriptionPlanRepository subscriptionPlans;
    private final CourseCategoryRepository courseCategories;
    private final GroupCourseRepository groupCourses;
    private final PrivateCourseRepository privateCourses;
    private final GroupClassSessionRepository groupClassSessions;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final PaymentTransactionRepository paymentTransactions;
    private final MemberSubscriptionRepository memberSubscriptions;
    private final MemberCourseQuotaRepository memberCourseQuotas;
    private final CouponRepository couponRepository;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    TestDataSeedRunner(
            AccountRepository accounts,
            MemberRepository members,
            EmployeeRepository employees,
            TrainerRepository trainers,
            ProductCategoryRepository productCategories,
            ProductRepository products,
            SubscriptionPlanRepository subscriptionPlans,
            CourseCategoryRepository courseCategories,
            GroupCourseRepository groupCourses,
            PrivateCourseRepository privateCourses,
            GroupClassSessionRepository groupClassSessions,
            OrderRepository orders,
            OrderItemRepository orderItems,
            PaymentTransactionRepository paymentTransactions,
            MemberSubscriptionRepository memberSubscriptions,
            MemberCourseQuotaRepository memberCourseQuotas,
            CouponRepository couponRepository,
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.test-data-enabled:false}") boolean enabled) {
        this.accounts = accounts;
        this.members = members;
        this.employees = employees;
        this.trainers = trainers;
        this.productCategories = productCategories;
        this.products = products;
        this.subscriptionPlans = subscriptionPlans;
        this.courseCategories = courseCategories;
        this.groupCourses = groupCourses;
        this.privateCourses = privateCourses;
        this.groupClassSessions = groupClassSessions;
        this.orders = orders;
        this.orderItems = orderItems;
        this.paymentTransactions = paymentTransactions;
        this.memberSubscriptions = memberSubscriptions;
        this.memberCourseQuotas = memberCourseQuotas;
        this.couponRepository = couponRepository;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) {
            log.info("測試帳號播種未啟用（app.seed.test-data-enabled=false），略過");
            return;
        }

        Account wendy = ensureAccount("wendy-test@local.test", AccountType.MEMBER);
        Member wendyMember = ensureMember(wendy, "美秀", "陳", "0911111111");

        Account admin = ensureAccount("emp-admin@local.test", AccountType.MEMBER, AccountType.EMPLOYEE);
        ensureMember(admin, "大同", "王", "0922222222");
        ensureEmployee(admin, "大同", "王", "0922222222", EmployeeRole.ADMIN);

        // 純管理員：刻意不建 member 列，roles 只有 ["EMPLOYEE","ADMIN"]。
        // emp-admin 帶著會員身分（適合展示角色疊加），但那也讓 NavBar 同時出現會員與管理
        // 兩個工作區；要呈現乾淨的管理端畫面時用這組，不必動用 AdminSeedRunner 的始祖帳號。
        Account adminOnly = ensureAccount("admin-only@local.test", AccountType.EMPLOYEE);
        ensureEmployee(adminOnly, "小美", "張", "0944444444", EmployeeRole.ADMIN);

        record TrainerSeed(String email, String lastName, String firstName, String phone, String specialty) {
        }
        List<TrainerSeed> trainerSeeds = List.of(
                new TrainerSeed("emp-trainer@local.test", "林", "志強", "0933333333", "重量訓練、肌力提升"),
                new TrainerSeed("emp-trainer2@local.test", "王", "志明", "0933333334", "重量訓練、心肺耐力"),
                new TrainerSeed("emp-trainer3@local.test", "張", "美玲", "0933333335", "空中瑜珈、柔軟伸展"),
                new TrainerSeed("emp-trainer4@local.test", "李", "健宏", "0933333336", "TRX懸吊、核心強化"),
                new TrainerSeed("emp-trainer5@local.test", "陳", "怡君", "0933333337", "Zumba、流動瑜珈"),
                new TrainerSeed("emp-trainer6@local.test", "黃", "冠宇", "0933333338", "重量訓練、爆發力訓練"),
                new TrainerSeed("emp-trainer7@local.test", "吳", "淑芬", "0933333339", "哈達瑜珈、冥想靜心"),
                new TrainerSeed("emp-trainer8@local.test", "徐", "浩然", "0933333340", "拳擊有氧、體能循環"),
                new TrainerSeed("emp-trainer9@local.test", "楊", "凱婷", "0933333341", "踏板有氧、皮拉提斯"),
                new TrainerSeed("emp-trainer10@local.test", "朱", "建國", "0933333342", "高強度間歇、敏捷反應"),
                new TrainerSeed("emp-trainer11@local.test", "蔡", "雅婷", "0933333343", "舒壓瑜珈、陰瑜珈"),
                new TrainerSeed("emp-trainer12@local.test", "許", "育誠", "0933333344", "徒手健身、核心雕塑"));

        List<Trainer> seededTrainers = new java.util.ArrayList<>();
        for (TrainerSeed ts : trainerSeeds) {
            Account acc = ensureAccount(ts.email(), AccountType.MEMBER, AccountType.EMPLOYEE);
            ensureMember(acc, ts.firstName(), ts.lastName(), ts.phone());
            Employee emp = ensureEmployee(acc, ts.firstName(), ts.lastName(), ts.phone(), EmployeeRole.EMPLOYEE);
            Trainer tr = ensureTrainer(emp, ts.specialty());
            seededTrainers.add(tr);
        }

        ensureCatalogData(seededTrainers);

        // 預約資格要等目錄資料就緒（訂閱要指向方案、堂數要指向教練）。
        // 放在種子最後而不是併進 ensureCatalogData：目錄資料是「大家看的展示資料」，
        // 這兩樣是「wendy 這個帳號的個人狀態」，語意不同。
        ensureActiveSubscription(wendyMember);
        ensureCourseQuota(wendyMember, seededTrainers.get(0));

        // 樣本訂單（含上面的訂閱訂單）都是直接建單、沒走 OrderService，補上狀態時間軸
        ensureSampleOrderHistory();

        log.info("測試帳號播種完成（密碼一律 {}；正式環境請以 SEED_TEST_DATA_ENABLED=false 停用）", TEST_PASSWORD);
    }

    /** 帳號不存在就建立；存在則導正為與密碼與角色集合 */
    private Account ensureAccount(String email, AccountType... roles) {
        Account account = accounts.findByEmailIgnoreCase(email).orElse(null);
        if (account == null) {
            account = new Account();
            account.setEmail(email);
            account.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
            for (AccountType role : roles) {
                account.getRoles().add(role);
            }
            log.info("已建立測試帳號 {}", email);
            return accounts.save(account);
        }
        if (account.getPasswordHash() == null
                || !passwordEncoder.matches(TEST_PASSWORD, account.getPasswordHash())) {
            account.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
            log.info("已將測試帳號 {} 的密碼重設為統一測試密碼", email);
        }
        for (AccountType role : roles) {
            if (account.getRoles().add(role)) {
                log.info("已為測試帳號 {} 補上 {} 身分", email, role);
            }
        }
        return accounts.save(account);
    }

    /** 會員列不存在就建立；存在則導正為 ACTIVE、未刪除、名字電話一致 */
    private Member ensureMember(Account account, String firstName, String lastName, String phone) {
        Member member = members.findByAccountId(account.getId()).orElse(null);
        if (member == null) {
            member = new Member();
            member.setAccount(account);
            member.setFirstName(firstName);
            member.setLastName(lastName);
            member.setPhone(phone);
            member.setStatus(UserStatus.ACTIVE);
            log.info("已建立測試會員 {}", account.getEmail());
            return members.save(member);
        }
        boolean changed = false;
        if (member.getStatus() != UserStatus.ACTIVE || member.getDeletedAt() != null) {
            member.setStatus(UserStatus.ACTIVE);
            member.setDeletedAt(null);
            changed = true;
            log.info("已將測試會員 {} 導正為 ACTIVE", account.getEmail());
        }
        if (!firstName.equals(member.getFirstName()) || !lastName.equals(member.getLastName())) {
            member.setFirstName(firstName);
            member.setLastName(lastName);
            changed = true;
            log.info("已導正測試會員 {} 姓名 → {}{}", account.getEmail(), lastName, firstName);
        }
        if (!phone.equals(member.getPhone())) {
            member.setPhone(phone);
            changed = true;
            log.info("已導正測試會員 {} 電話 → {}", account.getEmail(), phone);
        }
        if (changed) {
            member = members.save(member);
        }
        return member;
    }

    /** 員工列不存在就建立；存在則導正為 ACTIVE、指定職級、未刪除、姓名電話一致 */
    private Employee ensureEmployee(Account account, String firstName, String lastName,
            String phone, EmployeeRole role) {
        Employee employee = employees.findByAccountId(account.getId()).orElse(null);
        if (employee == null) {
            employee = new Employee();
            employee.setAccount(account);
            employee.setFirstName(firstName);
            employee.setLastName(lastName);
            employee.setPhone(phone);
            employee.setRole(role);
            employee.setStatus(EmployeeStatus.ACTIVE);
            employee.setHireDate(LocalDate.now());
            log.info("已建立測試員工 {}（{}）", account.getEmail(), role);
            return employees.save(employee);
        }
        boolean changed = false;
        if (employee.getStatus() != EmployeeStatus.ACTIVE || employee.getDeletedAt() != null) {
            employee.setStatus(EmployeeStatus.ACTIVE);
            employee.setDeletedAt(null);
            changed = true;
        }
        if (employee.getRole() != role) {
            employee.setRole(role);
            changed = true;
        }
        if (!firstName.equals(employee.getFirstName()) || !lastName.equals(employee.getLastName())) {
            employee.setFirstName(firstName);
            employee.setLastName(lastName);
            changed = true;
            log.info("已導正測試員工 {} 姓名 → {}{}", account.getEmail(), lastName, firstName);
        }
        if (!phone.equals(employee.getPhone())) {
            employee.setPhone(phone);
            changed = true;
        }
        if (changed) {
            employee = employees.save(employee);
            log.info("已將測試員工 {} 導正", account.getEmail());
        }
        return employee;
    }

    /** 教練列不存在就建立；存在則導正為未刪除，且姓名專長與 Seed 一致 */
    private Trainer ensureTrainer(Employee employee, String specialty) {
        Trainer trainer = trainers.findByEmployeeId(employee.getId()).orElse(null);
        if (trainer == null) {
            trainer = new Trainer();
            trainer.setEmployee(employee);
            trainer.setFirstName(employee.getFirstName());
            trainer.setLastName(employee.getLastName());
            trainer.setEmail(employee.getAccount().getEmail());
            trainer.setSpecialty(specialty);
            trainer.setExperience("五年一對一私人教練經驗");
            trainer.setBio("專注於" + specialty + "，致力於以科學且安全的方式帶領學員達到心目中的理想體態。");
            log.info("已建立專業教練（employee={}）", employee.getId());
            return trainers.save(trainer);
        }
        boolean changed = false;
        if (!employee.getFirstName().equals(trainer.getFirstName())
                || !employee.getLastName().equals(trainer.getLastName())) {
            trainer.setFirstName(employee.getFirstName());
            trainer.setLastName(employee.getLastName());
            changed = true;
            log.info("已導正教練姓名 → {}{}", employee.getLastName(), employee.getFirstName());
        }
        if (!specialty.equals(trainer.getSpecialty())) {
            trainer.setSpecialty(specialty);
            trainer.setBio("專注於" + specialty + "，致力於以科學且安全的方式帶領學員達到心目中的理想體態。");
            changed = true;
            log.info("已更新測試教練 {} 的專長為 {}", employee.getLastName() + employee.getFirstName(), specialty);
        }
        if (trainer.getDeletedAt() != null) {
            trainer.setDeletedAt(null);
            changed = true;
            log.info("已將測試教練（employee={}）導正為未刪除", employee.getId());
        }
        if (changed) {
            trainer = trainers.save(trainer);
        }
        return trainer;
    }

    // ─────────────── 預約資格：沒有這些，wendy 按報名／預約只會被擋 ───────────────

    /** 播種訂閱所建訂單的 order_no／merchantTradeNo；固定值以便辨識與冪等（見 ensureActiveSubscription） */
    private static final String SEED_SUBSCRIPTION_ORDER_NO = "SEED-SUB-001";

    /**
     * 讓 wendy 持有一筆有效訂閱：團課報名的資格檢查（GroupClassService#bookSession）
     * 查的是「未過期且 ACTIVE 的 MemberSubscription」，沒有它整條團課流程無從測起。
     *
     * <p>
     * 之所以連訂單一起建：member_subscription.order_id 是 NOT NULL。也因為這條 FK，
     * orders 主鍵型別遷移（PreSchemaFixupRunner）會把訂閱整列刪除——所以這個方法每次啟動
     * 都會重查重補，遷移清掉後下次啟動自動長回來。
     *
     * <p>
     * 訂單比照 ensureSampleOrders 的規矩：走正式建構子＋markPaid()，並補 PaymentTransaction
     * （退款後台以它為起點，缺了會 404）；以固定 order_no 冪等，不重複建單。
     */
    private void ensureActiveSubscription(Member member) {
        boolean hasActive = memberSubscriptions
                .findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                        member.getId(), LocalDateTime.now(), SubscriptionStatus.ACTIVE)
                .isPresent();
        if (hasActive) {
            return;
        }

        SubscriptionPlan plan = subscriptionPlans.findAll().stream().findFirst().orElse(null);
        if (plan == null) {
            log.warn("沒有任何訂閱方案，略過測試訂閱播種");
            return;
        }

        // 訂單可能在前次播種留下（訂閱被遷移刪除但訂單是新表重建後才建的），有就重用
        tw.com.ispan.gym.entity.order.Order order = orders.findAll().stream()
                .filter(o -> SEED_SUBSCRIPTION_ORDER_NO.equals(o.getOrderNo()))
                .findFirst()
                .orElse(null);
        if (order == null) {
            order = new tw.com.ispan.gym.entity.order.Order(
                    member, SEED_SUBSCRIPTION_ORDER_NO, SEED_SUBSCRIPTION_ORDER_NO,
                    plan.getPrice(), BigDecimal.ZERO, plan.getPrice(), plan.getPrice(), null,
                    member.getLastName() + member.getFirstName(), member.getPhone(), null,
                    member.getAccount().getEmail());
            order.markPaid("Credit_CreditCard", SEED_SUBSCRIPTION_ORDER_NO);
            order.setSubscriptionPlan(plan);
            order = orders.save(order);

            PaymentTransaction payment = new PaymentTransaction(
                    order, "ECPAY", SEED_SUBSCRIPTION_ORDER_NO, plan.getPrice(), "TWD");
            payment.setTradeNo(SEED_SUBSCRIPTION_ORDER_NO);
            payment.setPaymentMethod("Credit_CreditCard");
            payment.setStatus("SUCCESS");
            payment.setPaidAt(LocalDateTime.now());
            payment.setRtnCode(1);
            payment.setRtnMsg("交易成功");
            paymentTransactions.save(payment);
        }

        MemberSubscription subscription = new MemberSubscription();
        subscription.setMember(member);
        subscription.setOrder(order);
        subscription.setSubscriptionPlan(plan);
        subscription.setPlanName(plan.getName());
        // 起日往前推一天：閉區間邊界在「剛好現在」的比較上容易剛好差一瞬間，測試資料不玩這種邊緣
        subscription.setStartDate(LocalDateTime.now().minusDays(1));
        subscription.setEndDate(LocalDateTime.now().plusMonths(3));
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        memberSubscriptions.save(subscription);
        log.info("已為測試會員 {} 播種有效訂閱（方案：{}，效期 3 個月）",
                member.getAccount().getEmail(), plan.getName());
    }

    /**
     * 讓 wendy 有私教堂數可扣：預約檢查（MemberCourseQuotaRepository#findEligibleQuota）
     * 篩 remainingCourse > 0 且未過期，額度綁「會員＋教練」。
     * private_course 與 order_item 皆可為 null——正式流程是買課後產生，這裡是直接發放。
     */
    private void ensureCourseQuota(Member member, Trainer trainer) {
        if (!memberCourseQuotas.findEligibleQuota(member.getId(), trainer.getId()).isEmpty()) {
            return;
        }

        MemberCourseQuota quota = new MemberCourseQuota();
        quota.setMember(member);
        quota.setTrainer(trainer);
        quota.setCourseName("一對一重量訓練額度");
        quota.setRemainingCourse(10);
        quota.setReservedCourse(0);
        quota.setUsedCourse(0);
        quota.setExpiryDate(LocalDateTime.now().plusMonths(6));
        memberCourseQuotas.save(quota);
        log.info("已為測試會員 {} 播種私教堂數 10 堂（教練 id={}）",
                member.getAccount().getEmail(), trainer.getId());
    }

    // ─────────────── 目錄資料：讓前端頁面有東西可顯示 ───────────────

    /**
     * 商品／訂閱方案／團課等前台頁面若沒有資料，畫面只會顯示空狀態，
     * 無從判斷版面與串接是否正確。這裡補上最小一組可展示的資料。
     *
     * <p>
     * 判斷方式一律是「該表為空才建立」而非逐筆比對——這些是展示用資料，
     * 組員自己在後台新增的內容不該被本 runner 覆蓋或刪除。
     */
    private void ensureCatalogData(List<Trainer> seededTrainers) {
        ensure46Products();
        ensureSubscriptionPlans();
        // 清除舊的一對一私教分類與舊課程，因為私教課只適用重量訓練分類
        jdbc.execute(
                "DELETE FROM private_course WHERE course_category_id IN (SELECT course_category_id FROM course_category WHERE name = '一對一私教')");
        jdbc.execute("DELETE FROM course_category WHERE name = '一對一私教'");

        long distinctTrainers = groupCourses.findAll().stream().map(gc -> gc.getTrainer().getId()).distinct().count();
        boolean hasInvalidPrivateTrainer = privateCourses.findAll().stream()
                .anyMatch(pc -> pc.getTrainer() == null || !pc.getTrainer().getSpecialty().contains("重量訓練"));
        boolean hasBodySculptingCourse = privateCourses.findAll().stream()
                .anyMatch(pc -> pc.getName().contains("體態雕塑"));
        if (distinctTrainers <= 1 || groupCourses.count() < 16 || privateCourses.count() != 3
                || hasInvalidPrivateTrainer || hasBodySculptingCourse) {
            log.info("團體課程教練未分散、私教課數不為 3、教練專長不符或含有舊私教名稱，重新清除並播種以分配至 12 位教練…");
            jdbc.execute("DELETE FROM group_class_booking");
            jdbc.execute("DELETE FROM session_deduction_log");
            jdbc.execute("DELETE FROM coach_booking");
            jdbc.execute("DELETE FROM coach_slot");
            jdbc.execute("DELETE FROM order_item");
            jdbc.execute("DELETE FROM payment_refund");
            jdbc.execute("DELETE FROM payment_transaction");
            jdbc.execute("DELETE FROM payment_webhook");
            jdbc.execute("DELETE FROM order_status_history");
            jdbc.execute("DELETE FROM member_subscription");
            jdbc.execute("DELETE FROM orders");
            jdbc.execute("DELETE FROM group_class_session");
            jdbc.execute("DELETE FROM group_course");
            jdbc.execute("DELETE FROM private_course");
        }
        CourseCategory groupCategory = ensureCourseCategory();
        ensureGroupCourseAndSession(seededTrainers, groupCategory);
        ensurePrivateCourses(seededTrainers);
        ensure17Coupons();
        ensureSampleOrders();
    }

    /**
     * 商品訂單樣本：讓營運統計頁的排行、每日營收趨勢與狀態分佈有足夠資料可看，
     * 只有三兩筆時圖表幾乎沒有意義。
     *
     * <p>
     * 以 order_no 前綴 {@value #SAMPLE_ORDER_PREFIX} 辨識是否已播種，補齊到固定筆數即停手，
     * 不碰任何真實下單流程產生的訂單。日期刻意散佈在最近三十天內、狀態涵蓋
     * PAID／UNPAID／REFUNDED／CANCELLED，讓分佈圖表不會只有單一色塊。
     */
    private void ensureSampleOrders() {
        Member buyer = members.findAll().stream().findFirst().orElse(null);
        if (buyer == null) {
            return;
        }
        long existing = orders.findAll().stream()
                .filter(o -> o.getOrderNo() != null && o.getOrderNo().startsWith(SAMPLE_ORDER_PREFIX))
                .count();
        if (existing >= SAMPLE_ORDER_COUNT) {
            return;
        }

        List<Product> sellable = products.findAll().stream()
                .filter(p -> p.getPrice() != null)
                .toList();
        if (sellable.isEmpty()) {
            return;
        }

        // 狀態與「幾天前」的組合寫死，播種結果才可重現，不同機器看到的圖表一致
        record Seed(int daysAgo, OrderStatus status, int productIndex, int quantity) {
        }
        List<Seed> seeds = List.of(
                new Seed(27, OrderStatus.PAID, 0, 2),
                new Seed(24, OrderStatus.PAID, 1, 1),
                new Seed(21, OrderStatus.REFUNDED, 2, 3),
                new Seed(18, OrderStatus.PAID, 3, 1),
                new Seed(15, OrderStatus.CANCELLED, 4, 2),
                new Seed(12, OrderStatus.PAID, 5, 1),
                new Seed(9, OrderStatus.PAID, 6, 4),
                new Seed(6, OrderStatus.UNPAID, 7, 1),
                new Seed(4, OrderStatus.PAID, 8, 2),
                new Seed(1, OrderStatus.PAID, 9, 1));

        int created = 0;
        for (int i = (int) existing; i < seeds.size() && i < SAMPLE_ORDER_COUNT; i++) {
            Seed seed = seeds.get(i);
            Product product = sellable.get(seed.productIndex() % sellable.size());
            BigDecimal unit = product.getPrice();
            BigDecimal amount = unit.multiply(BigDecimal.valueOf(seed.quantity()));
            LocalDateTime placedAt = LocalDateTime.now().minusDays(seed.daysAgo());

            String no = SAMPLE_ORDER_PREFIX + String.format("%03d", i + 1);
            // 走正式建構子＋狀態轉移方法，不硬塞欄位：Order 刻意只開放 cancel()／markPaid()
            // 等方法來改狀態（會檢查前置狀態並同步 paidAt／cancelledAt），繞過去等於自己
            // 造出一個業務規則不允許的狀態組合
            tw.com.ispan.gym.entity.order.Order order = new tw.com.ispan.gym.entity.order.Order(
                    buyer, no, no, amount, BigDecimal.ZERO, amount, amount, null,
                    buyer.getLastName() + buyer.getFirstName(), buyer.getPhone(), null, null);
            switch (seed.status()) {
                case PAID -> order.markPaid("Credit_CreditCard", no);
                case REFUNDED -> {
                    order.markPaid("Credit_CreditCard", no);
                    order.markRefunded();
                }
                case CANCELLED -> order.cancel("測試資料：模擬取消訂單");
                default -> {
                    /* UNPAID 是建構子的預設狀態，不必轉移 */ }
            }
            tw.com.ispan.gym.entity.order.Order savedOrder = orders.save(order);
            orderItems.save(new OrderItem(savedOrder, product, product.getName(), unit,
                    seed.quantity()));

            // 付款過的訂單要一併補 PaymentTransaction：退款流程是以它為起點
            // （OrderService.requestRefund 找不到付款紀錄會回 404「找不到這筆訂單的付款紀錄」），
            // 只把 orders.status 設成 PAID 而沒有這筆，後台退款流程根本走不了。
            // 正式流程由 ECPay webhook 建立（見 EcpayService），這裡比照它成功時的欄位設定。
            if (seed.status() == OrderStatus.PAID || seed.status() == OrderStatus.REFUNDED) {
                PaymentTransaction payment = new PaymentTransaction(
                        savedOrder, "ECPAY", no, amount, "TWD");
                payment.setTradeNo(no);
                payment.setPaymentMethod("Credit_CreditCard");
                payment.setStatus("SUCCESS");
                payment.setPaidAt(placedAt);
                payment.setRtnCode(1);
                payment.setRtnMsg("交易成功");
                paymentTransactions.save(payment);
            }

            // created_at 由 TaipeiTimestampEntity 的 @PrePersist 強制設成「現在」，
            // 不改的話十筆訂單全部落在同一天，趨勢圖只會有一個點。這裡直接下 SQL 回填
            // 分散的日期——是刻意繞過 JPA 的稽核欄位，僅限測試資料播種使用。
            jdbc.update("UPDATE orders SET created_at = ?, updated_at = ? WHERE order_id = ?",
                    placedAt, placedAt, savedOrder.getOrderId());
            jdbc.update("UPDATE order_item SET created_at = ? WHERE order_id = ?",
                    placedAt, savedOrder.getOrderId());
            if (savedOrder.getPaidAt() != null) {
                jdbc.update("UPDATE orders SET paid_at = ? WHERE order_id = ?", placedAt, savedOrder.getOrderId());
            }
            created++;
        }
        if (created > 0) {
            log.info("已建立 {} 筆測試訂單（狀態涵蓋 PAID／UNPAID／REFUNDED／CANCELLED）", created);
        }
    }

    /**
     * 樣本訂單（{@link #ensureSampleOrders} 的 SEED-ORD- 與
     * {@link #ensureActiveSubscription}
     * 的 SEED-SUB-）為了控制日期分佈直接建單、沒有走 {@code OrderService}，因此不會留下
     * order_status_history，「我的訂單」／後台訂單展開後就看不到時間軸。這裡冪等補上：
     * 只挑 order_no 以 {@code SEED-} 開頭、且目前完全沒有任何歷程列的訂單，依現況狀態推出
     * 對應事件（note 短代碼對齊前端 historyNoteMeta）。時間戳直接下 SQL——{@code changed_at}
     * 是 {@code @CreatedDate}／{@code updatable=false}，跟本檔其他日期回填同樣做法。
     */
    private void ensureSampleOrderHistory() {
        List<Map<String, Object>> seedOrders = jdbc.queryForList(
                "SELECT order_id as id, status, created_at, paid_at, member_id FROM orders WHERE order_no LIKE 'SEED-%'");
        int filled = 0;
        for (Map<String, Object> row : seedOrders) {
            int orderId = ((Number) row.get("id")).intValue();
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", Integer.class, orderId);
            if (existing != null && existing > 0) {
                continue;
            }
            String status = String.valueOf(row.get("status"));
            Integer memberId = row.get("member_id") == null ? null : ((Number) row.get("member_id")).intValue();
            // 讀出來的日期原樣寫回 changed_at，跟 orders.created_at 是同一組值（同樣經 Hibernate 讀取時的
            // jdbc.time_zone=UTC 位移），前端時間軸與訂單成立時間才會對齊
            LocalDateTime createdAt = toLocalDateTime(row.get("created_at"));
            LocalDateTime paidAt = row.get("paid_at") == null ? createdAt : toLocalDateTime(row.get("paid_at"));

            insertOrderHistory(orderId, null, "UNPAID", memberId, "USER", "CHECKOUT", createdAt);
            switch (status) {
                case "PAID" ->
                    insertOrderHistory(orderId, "UNPAID", "PAID", memberId, "SYSTEM", "ECPAY_NOTIFY", paidAt);
                case "CANCELLED" -> insertOrderHistory(orderId, "UNPAID", "CANCELLED", memberId, "USER", "CANCEL",
                        createdAt.plusHours(1));
                case "REFUNDED" -> {
                    insertOrderHistory(orderId, "UNPAID", "PAID", memberId, "SYSTEM", "ECPAY_NOTIFY", paidAt);
                    insertOrderHistory(orderId, "PAID", "PAID", memberId, "USER", "REFUND_REQUEST", paidAt.plusDays(1));
                    insertOrderHistory(orderId, "PAID", "REFUNDED", memberId, "ADMIN", "REFUND_COMPLETE",
                            paidAt.plusDays(2));
                }
                default -> {
                    /* UNPAID／FAILED：只留 CHECKOUT 一列 */ }
            }
            filled++;
        }
        if (filled > 0) {
            log.info("已為 {} 筆樣本訂單補上狀態時間軸", filled);
        }
    }

    private void insertOrderHistory(int orderId, String fromStatus, String toStatus, Integer changedById,
            String changeSource, String note, LocalDateTime changedAt) {
        jdbc.update("INSERT INTO order_status_history "
                + "(order_id, from_status, to_status, changed_by_id, change_source, note, changed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, fromStatus, toStatus, changedById, changeSource, note, changedAt);
    }

    /**
     * queryForList 對 datetime2 可能回 java.sql.Timestamp 或 LocalDateTime，統一成牆上時間的
     * LocalDateTime
     */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        throw new IllegalStateException("非預期的日期型別：" + (value == null ? "null" : value.getClass()));
    }

    private ProductCategory getOrCreateCategory(String name) {
        return productCategories.findByName(name).orElseGet(() -> {
            ProductCategory cat = new ProductCategory();
            cat.setName(name);
            return productCategories.save(cat);
        });
    }

    private void ensure46Products() {
        record Seed(String name, String price, int stock, String brand, String flavor, String desc,
                String catName, String size, String color, String material, String origin, Double weight) {
        }
        List<Seed> seeds = List.of(
                // 補給品 (16 items)
                new Seed("分離乳清蛋白-巧克力", "1480", 50, "MyProtein", "巧克力", "分離乳清蛋白，吸收更快。", "補給品", "1kg", "無", "粉末", "英國",
                        1.0),
                new Seed("分離乳清蛋白-香草", "1480", 45, "MyProtein", "香草", "高品質分離乳清蛋白。", "補給品", "1kg", "無", "粉末", "英國", 1.0),
                new Seed("植物性蛋白粉-草莓", "1100", 30, "Optimum", "草莓", "全植物性蛋白，純素可食。", "補給品", "500g", "無", "粉末", "美國", 0.5),
                new Seed("微粉化肌酸", "750", 40, "Optimum", "無味", "提升爆發力，微粉化易溶解。", "補給品", "300g", "無", "粉末", "美國", 0.3),
                new Seed("發泡錠-維生素C", "180", 100, "Redoxon", "柳橙", "每日維生素C與鋅補充。", "補給品", "15錠", "橘色", "錠劑", "德國", 0.1),
                new Seed("電解質補給粉-檸檬", "350", 80, "Pocari", "檸檬", "運動中補水與電解質防抽筋。", "補給品", "30包", "黃色", "粉末", "日本", 0.2),
                new Seed("左旋肉鹼液", "880", 35, "Muscletech", "野莓", "液態左旋肉鹼，促進代謝。", "補給品", "480ml", "紅色", "液態", "美國", 0.5),
                new Seed("高效氮泵前訓練-西瓜", "1280", 25, "C4", "西瓜", "爆發力配方，提神專注。", "補給品", "390g", "綠色", "粉末", "美國", 0.4),
                new Seed("綜合維他命加強錠", "600", 60, "Centrum", "無味", "專為運動人士設計的維生素。", "補給品", "120錠", "橘黃", "錠劑", "美國",
                        0.15),
                new Seed("鋅鎂膠囊(ZMA)", "680", 55, "Now Foods", "無味", "幫助睡眠恢復與睪酮分泌。", "補給品", "90顆", "黑白", "膠囊", "美國",
                        0.1),
                new Seed("魚油軟膠囊", "800", 70, "Kirkland", "無味", "高品質Omega-3保護關節與心血管。", "補給品", "400顆", "黃色", "軟膠囊", "美國",
                        0.5),
                new Seed("高蛋白能量棒-花生", "95", 120, "Quest", "花生巧克力", "低糖高纖，方便隨身補充。", "補給品", "60g/條", "棕白", "固體", "美國",
                        0.06),
                new Seed("高蛋白曲奇餅", "110", 90, "Lenny", "巧克力豆", "軟餅乾口感，大分量蛋白質補充。", "補給品", "113g/包", "棕色", "固體", "美國",
                        0.11),
                new Seed("BCAA 3:1:2 膠囊", "720", 40, "MusclePharm", "無味", "修復肌肉，減緩酸痛。", "補給品", "240顆", "白色", "膠囊", "美國",
                        0.25),
                new Seed("穀氨醯胺粉", "890", 30, "Optimum", "無味", "加速肌肉修復與免疫系統健康。", "補給品", "300g", "白色", "粉末", "美國", 0.3),
                new Seed("關節保養葡萄糖胺", "980", 50, "Move Free", "無味", "保護關節軟骨，減緩磨損。", "補給品", "120錠", "綠白", "錠劑", "美國",
                        0.15),

                // 器材 (15 items)
                new Seed("環保瑜珈墊 6mm", "980", 30, "Manduka", "黑", "止滑防汗，環保TPE材質。", "器材", "180x61cm", "黑", "TPE", "德國",
                        1.5),
                new Seed("防滑乳膠阻力圈", "250", 80, "TheraBand", "紅-中度", "適合臀腿訓練與暖身激活。", "器材", "標準", "紅", "乳膠", "臺灣", 0.1),
                new Seed("奧林匹克長槓 2.2m", "5800", 10, "Rogue", "鋼鐵銀", "承重1000磅，專業舉重槓。", "器材", "2.2m", "銀", "鋼鐵", "美國",
                        20.0),
                new Seed("專業環保壺鈴 8kg", "980", 15, "Alex", "黑", "鑄鐵包膠，防滑手把。", "器材", "8kg", "黑", "鑄鐵包膠", "臺灣", 8.0),
                new Seed("專業環保壺鈴 12kg", "1380", 15, "Alex", "黑", "鑄鐵包膠，防滑手把。", "器材", "12kg", "黑", "鑄鐵包膠", "臺灣", 12.0),
                new Seed("專業環保壺鈴 16kg", "1780", 10, "Alex", "黑", "鑄鐵包膠，防滑手把。", "器材", "16kg", "黑", "鑄鐵包膠", "臺灣", 16.0),
                new Seed("六角橡膠啞鈴 5kg", "600", 20, "Alex", "黑", "六角設計防滾動，減震降噪。", "器材", "5kg", "黑", "橡膠鑄鐵", "臺灣", 5.0),
                new Seed("六角橡膠啞鈴 10kg", "1100", 20, "Alex", "黑", "六角設計防滾動，減震降噪。", "器材", "10kg", "黑", "橡膠鑄鐵", "臺灣",
                        10.0),
                new Seed("六角橡膠啞鈴 15kg", "1650", 15, "Alex", "黑", "六角設計防滾動，減震降噪。", "器材", "15kg", "黑", "橡膠鑄鐵", "臺灣",
                        15.0),
                new Seed("六角橡膠啞鈴 20kg", "2200", 10, "Alex", "黑", "六角設計防滾動，減震降噪。", "器材", "20kg", "黑", "橡膠鑄鐵", "臺灣",
                        20.0),
                new Seed("高密度泡沫按摩滾軸", "450", 40, "TriggerPoint", "橘色", "深層筋膜放鬆，舒緩酸痛。", "器材", "33x14cm", "橘", "泡沫", "美國",
                        0.5),
                new Seed("加厚阻力帶-拉力繩", "380", 50, "TheraBand", "綠-重度", "適合阻力訓練與引體向上輔助。", "器材", "標準", "綠", "乳膠", "臺灣",
                        0.3),
                new Seed("多功能實心藥球 4kg", "750", 15, "Alex", "黑黃", "核心訓練與爆發力投擲。", "器材", "4kg", "黑黃", "橡膠", "臺灣", 4.0),
                new Seed("多功能實心藥球 6kg", "950", 12, "Alex", "黑紅", "核心訓練與爆發力投擲。", "器材", "6kg", "黑紅", "橡膠", "臺灣", 6.0),
                new Seed("高彈力抗暴瑜珈球", "620", 25, "Nike", "藍", "核心穩定訓練與伸展。", "器材", "65cm", "藍", "PVC", "中國", 0.8),

                // 配件 (15 items)
                new Seed("牛皮健身腰帶", "1580", 20, "Valeo", "棕色", "硬舉深蹲核心支撐，厚度10mm。", "配件", "M", "棕", "真皮", "美國", 0.9),
                new Seed("專業重訓拉力帶", "450", 60, "Harbinger", "黑色", "護腕拉力帶一體，防滑耐磨。", "配件", "均碼", "黑", "尼龍", "美國", 0.15),
                new Seed("透氣運動防滑手套", "520", 50, "Nike", "灰黑", "透氣網布，掌心避震橡膠。", "配件", "M", "灰黑", "尼龍橡膠", "中國", 0.1),
                new Seed("加壓型針織護膝(一對)", "780", 40, "Bauerfeind", "藍灰", "3D針織加壓保護，減輕膝蓋負擔。", "配件", "M", "藍灰", "針織", "德國",
                        0.2),
                new Seed("專業運動防滑粉", "150", 150, "Rogue", "白", "強力吸汗防滑，舉重體操專用。", "配件", "150g", "白", "碳酸鎂粉", "美國", 0.15),
                new Seed("高階運動水壺 1000ml", "290", 80, "Nalgene", "透明灰", "Tritan安全材質，防漏耐摔。", "配件", "1000ml", "透明灰",
                        "Tritan", "美國", 0.2),
                new Seed("多功能乾濕分離運動包", "1280", 30, "UnderArmour", "深藍", "底部獨立鞋袋，乾濕分離設計。", "配件", "35L", "深藍", "尼龍", "中國",
                        0.8),
                new Seed("超細纖維吸汗運動毛巾", "180", 100, "Willy", "綠色", "吸水快乾，健身房擦汗必備。", "配件", "100x20cm", "綠", "超細纖維", "臺灣",
                        0.1),
                new Seed("運動排汗止汗頭帶", "150", 120, "Nike", "白色", "高彈力排汗材質，防止汗水入眼。", "配件", "均碼", "白", "彈性纖維", "中國", 0.05),
                new Seed("便攜式滾輪按摩棒", "480", 40, "TriggerPoint", "黑綠", "便攜手動滾輪，隨時舒緩肌肉。", "配件", "40cm", "黑綠", "ABS橡膠",
                        "美國", 0.3),
                new Seed("專業重訓護腕(一對)", "380", 50, "Schiek", "黑紅", "大重量支撐，高彈力帶。", "配件", "均碼", "黑紅", "棉彈力布", "美國", 0.2),
                new Seed("阻力帶專用收納袋", "120", 200, "TheraBand", "黑色", "防塵收納，方便攜帶。", "配件", "均碼", "黑", "尼龍", "臺灣", 0.05),
                new Seed("專業鋼絲跳繩", "350", 70, "Rogue", "黑色", "高速培林，適合有氧與雙下跳。", "配件", "3m可調", "黑", "鋼絲", "美國", 0.2),
                new Seed("運動護肘加壓帶(一對)", "680", 30, "Bauerfeind", "黑色", "肘關節保護，減緩網球肘症狀。", "配件", "M", "黑", "針織", "德國",
                        0.15),
                new Seed("防滑瑜珈鋪巾", "890", 35, "Manduka", "紫色", "鋪於瑜珈墊上防滑吸汗，可機洗。", "配件", "180x61cm", "紫", "超細纖維", "德國",
                        0.6));

        int created = 0;
        int updated = 0;
        for (Seed seed : seeds) {
            java.util.Optional<Product> existing = products.findAll().stream()
                    .filter(p -> seed.name().equals(p.getName()))
                    .findFirst();
            if (existing.isPresent()) {
                // 補齊缺漏的規格欄位（冪等：已有值則不覆蓋）
                Product p = existing.get();
                boolean changed = false;
                if (p.getSize() == null) {
                    p.setSize(seed.size());
                    changed = true;
                }
                if (p.getColor() == null) {
                    p.setColor(seed.color());
                    changed = true;
                }
                if (p.getMaterial() == null) {
                    p.setMaterial(seed.material());
                    changed = true;
                }
                if (p.getOrigin() == null) {
                    p.setOrigin(seed.origin());
                    changed = true;
                }
                if (p.getWeight() == null) {
                    p.setWeight(seed.weight());
                    changed = true;
                }
                if (changed) {
                    products.save(p);
                    updated++;
                }
                continue;
            }
            ProductCategory category = getOrCreateCategory(seed.catName());
            Product product = new Product();
            product.setName(seed.name());
            product.setPrice(new BigDecimal(seed.price()));
            product.setStock(seed.stock());
            product.setBrand(seed.brand());
            product.setFlavor(seed.flavor());
            product.setDescription(seed.desc());
            product.setProductCategory(category);
            product.setSize(seed.size());
            product.setColor(seed.color());
            product.setMaterial(seed.material());
            product.setOrigin(seed.origin());
            product.setWeight(seed.weight());
            products.save(product);
            created++;
        }
        if (created > 0 || updated > 0) {
            log.info("測試商品播種：新建 {} 筆，補齊規格 {} 筆", created, updated);
        }
    }

    private void ensureSubscriptionPlans() {
        if (!subscriptionPlans.findAll().isEmpty()) {
            return;
        }
        record Seed(String name, String price, int days, String desc) {
        }
        List<Seed> seeds = List.of(
                new Seed("月費方案", "1500", 30, "單月無限次入場，可報名團體課程。"),
                new Seed("季費方案", "4200", 90, "三個月方案，平均每月 1400 元。"),
                new Seed("年費方案", "15000", 365, "最超值方案，平均每月 1250 元。"));
        for (Seed seed : seeds) {
            SubscriptionPlan plan = new SubscriptionPlan();
            plan.setName(seed.name());
            plan.setPrice(new BigDecimal(seed.price()));
            plan.setDurationDays(seed.days());
            plan.setDescription(seed.desc());
            subscriptionPlans.save(plan);
        }
        log.info("已建立 {} 筆測試訂閱方案", seeds.size());
    }

    /** 確保 4 個課程分類存在，回傳分類 Map (名稱 -> 實體)；同時清除不在名單內的多餘分類 */
    private java.util.Map<String, CourseCategory> ensureAllCourseCategories() {
        String[] names = { "有氧燃脂", "重量訓練", "瑜珈伸展", "功能性訓練" };
        java.util.Set<String> canonical = java.util.Set.of(names);
        java.util.Map<String, CourseCategory> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            final String name = names[i];
            final int order = i + 1;
            CourseCategory cat = courseCategories.findByName(name).orElseGet(() -> {
                CourseCategory c = new CourseCategory();
                c.setName(name);
                c.setSortOrder(order);
                CourseCategory saved = courseCategories.save(c);
                log.info("已建立課程分類：{}", saved.getName());
                return saved;
            });
            map.put(name, cat);
        }
        // 清除資料庫中不在標準名單的多餘分類（安全：僅刪除無課程的分類）
        courseCategories.findAll().forEach(cat -> {
            if (!canonical.contains(cat.getName())) {
                try {
                    courseCategories.delete(cat);
                    log.info("已刪除多餘課程分類：{}", cat.getName());
                } catch (Exception e) {
                    log.warn("無法刪除分類「{}」（可能仍有課程關聯）：{}", cat.getName(), e.getMessage());
                }
            }
        });
        return map;
    }

    private CourseCategory ensureCourseCategory() {
        java.util.Map<String, CourseCategory> cats = ensureAllCourseCategories();
        return cats.values().iterator().next();
    }

    /**
     * 團課：建立 4 分類 × 4 課程 = 16 筆，每筆附 1 個未來場次。
     * 表非空時直接 return，保留後台手動新增的資料。
     */
    private void ensureGroupCourseAndSession(List<Trainer> seededTrainers, CourseCategory ignoredCategory) {
        if (!groupCourses.findAll().isEmpty()) {
            return;
        }
        if (seededTrainers.isEmpty()) {
            log.warn("找不到測試教練，略過團課播種");
            return;
        }
        java.util.Map<String, CourseCategory> cats = ensureAllCourseCategories();
        CourseCategory catAero = cats.get("有氧燃脂");
        CourseCategory catWeight = cats.get("重量訓練");
        CourseCategory catYoga = cats.get("瑜珈伸展");
        CourseCategory catFunc = cats.get("功能性訓練");

        // 16 筆種子資料：name, desc, category, capacity, room, sessionHour
        record GCSeed(String name, String desc, CourseCategory cat, int cap, ClassRoom room, int hour) {
        }
        List<GCSeed> seeds = List.of(
                // 有氧燃脂 × 4
                new GCSeed("高強度燃脂班", "HIIT 交替訓練，心率拉高極限、快速燃燒體脂。", catAero, 20, ClassRoom.BOXING_ROOM, 19),
                new GCSeed("舞動有氧", "融合舞蹈律動與有氧訓練，輕鬆享受燃脂樂趣。", catAero, 25, ClassRoom.BOXING_ROOM, 20),
                new GCSeed("跳繩體能訓練", "跳繩搭配體能循環動作，提升心肺耐力與協調力。", catAero, 18, ClassRoom.BOXING_ROOM, 18),
                new GCSeed("自行車有氧", "室內飛輪騎乘，間歇爬坡訓練強化下肢並燃燒熱量。", catAero, 15, ClassRoom.BOXING_ROOM, 7),
                // 重量訓練 × 4
                new GCSeed("基礎槓鈴訓練", "深蹲、硬舉、臥推三大動作紮實入門，安全第一。", catWeight, 12, ClassRoom.WEIGHT_ROOM, 9),
                new GCSeed("壺鈴全身訓練", "壺鈴擺盪與抓舉動作，全身性力量與爆發力訓練。", catWeight, 12, ClassRoom.WEIGHT_ROOM, 10),
                new GCSeed("啞鈴雕塑班", "上下肢分離訓練，針對肌肉線條雕塑進行系統訓練。", catWeight, 15, ClassRoom.WEIGHT_ROOM, 18),
                new GCSeed("TRX 懸吊訓練", "利用自體重量與 TRX 帶進行核心與全身肌力訓練。", catWeight, 10, ClassRoom.WEIGHT_ROOM, 19),
                // 瑜珈伸展 × 4
                new GCSeed("哈達瑜珈入門", "呼吸與體位法結合，適合初學者的舒緩瑜珈課程。", catYoga, 20, ClassRoom.YOGA_ROOM, 8),
                new GCSeed("陰瑜珈深層放鬆", "長時間保持體式以鬆解筋膜，深層修復疲勞肌群。", catYoga, 18, ClassRoom.YOGA_ROOM, 9),
                new GCSeed("流動瑜珈", "連貫式流動序列訓練，兼顧柔軟度與平衡感。", catYoga, 15, ClassRoom.YOGA_ROOM, 10),
                new GCSeed("瑜珈伸展舒壓班", "針對肩頸腰背等緊繃部位進行深度伸展，消除久坐痠痛。", catYoga, 20, ClassRoom.YOGA_ROOM, 20),
                // 功能性訓練 × 4
                new GCSeed("體能循環訓練", "多站式循環動作設計，全方位提升心肺與肌力。", catFunc, 15, ClassRoom.WEIGHT_ROOM, 18),
                new GCSeed("核心穩定訓練", "核心肌肉群鍛鍊，改善體態與脊椎穩定度。", catFunc, 12, ClassRoom.WEIGHT_ROOM, 19),
                new GCSeed("敏捷速度訓練", "步伐反應速度訓練與身體靈活性控制。", catFunc, 10, ClassRoom.BOXING_ROOM, 20),
                new GCSeed("體重訓練入門", "自體重量肌力鍛鍊，徒手全身核心爆發力鍛鍊。", catFunc, 20, ClassRoom.BOXING_ROOM, 9));

        int dayOffset = 3;
        for (int i = 0; i < seeds.size(); i++) {
            GCSeed seed = seeds.get(i);
            GroupCourse gc = new GroupCourse();
            // 輪流分配給 12 位教練
            Trainer trainer = seededTrainers.get(i % seededTrainers.size());
            gc.setTrainer(trainer);
            gc.setName(seed.name());
            gc.setDescription(seed.desc());
            gc.setCategory(seed.cat());
            gc.setMaxCapacity(seed.cap());
            gc.setStatus(CourseStatus.ACTIVE);
            GroupCourse saved = groupCourses.save(gc);

            LocalDateTime start = LocalDateTime.now().plusDays(dayOffset)
                    .withHour(seed.hour()).withMinute(0).withSecond(0).withNano(0);
            GroupClassSession session = new GroupClassSession();
            session.setCourse(saved);
            session.setRoomName(seed.room());
            session.setStartAt(start);
            session.setEndAt(start.plusHours(1));
            session.setCapacity(seed.cap());
            session.setStatus(GroupClassSessionStatus.SCHEDULED);
            groupClassSessions.save(session);
            dayOffset++;
        }
        log.info("已建立 {} 筆團課主檔與未來場次（4 分類各 4 筆，由 12 位教練分擔）", seeds.size());
    }

    /**
     * 私教課方案：全部歸屬「重量訓練」分類。
     */
    private void ensurePrivateCourses(List<Trainer> seededTrainers) {
        if (!privateCourses.findByStatus(CourseStatus.ACTIVE).isEmpty()) {
            return;
        }
        if (seededTrainers.isEmpty()) {
            log.warn("找不到測試教練，略過私教課播種");
            return;
        }
        // 私教課一律掛在「重量訓練」分類下
        java.util.Map<String, CourseCategory> cats = ensureAllCourseCategories();
        CourseCategory category = cats.get("重量訓練");

        record Seed(String name, String desc, int price, int sessions, boolean trial) {
        }
        List<Seed> seeds = List.of(
                new Seed("一對一重訓體驗課", "首次體驗，單堂一對一指導，評估體能並初步規劃重訓方向。", 800, 1, true),
                new Seed("重訓入門計畫（5堂）", "從姿勢與呼吸打底，建立深蹲、硬舉、臥推三大動作基礎。", 6500, 5, false),
                new Seed("重訓進階計畫（10堂）", "依 InBody 數據客製課表，系統化進行進階重訓，十堂完成階段性目標。", 12000, 10, false));

        List<Trainer> weightTrainers = seededTrainers.stream()
                .filter(t -> t.getSpecialty() != null && t.getSpecialty().contains("重量訓練"))
                .toList();

        for (int i = 0; i < seeds.size(); i++) {
            Seed seed = seeds.get(i);
            PrivateCourse course = new PrivateCourse();
            course.setName(seed.name());
            course.setDescription(seed.desc());
            course.setPrice(new BigDecimal(seed.price()));
            course.setSessionPerUnit(seed.sessions());
            Trainer trainer = weightTrainers.isEmpty() ? seededTrainers.get(i % seededTrainers.size())
                    : weightTrainers.get(i % weightTrainers.size());
            course.setTrainer(trainer);
            course.setCategory(category);
            course.setIsTrial(seed.trial());
            course.setStatus(CourseStatus.ACTIVE);
            privateCourses.save(course);
        }
        log.info("已建立 {} 筆私教課方案（分類：重量訓練）", seeds.size());
    }

    private void ensure17Coupons() {
        record CouponSeed(String name, String code, CouponType type, BigDecimal value, BigDecimal minSpend,
                CouponApplicableType applicableType, String description) {
        }
        List<CouponSeed> seeds = List.of(
                new CouponSeed("新店開張大紅包", "NEWOPEN100", CouponType.CASH, new BigDecimal("100"), new BigDecimal("500"),
                        CouponApplicableType.ALL, "慶祝新店開幕，全館滿500折100！"),
                new CouponSeed("體驗會籍折價券", "MEMB150", CouponType.CASH, new BigDecimal("150"), new BigDecimal("1500"),
                        CouponApplicableType.SUBSCRIPTION, "會籍方案專屬，滿1500折150！"),
                new CouponSeed("高級補給品八五折", "SUPP85", CouponType.PERCENT, new BigDecimal("15"), new BigDecimal("1000"),
                        CouponApplicableType.PRODUCT, "商城補給品專用，滿1000享85折優惠！"),
                new CouponSeed("團體有氧體驗券", "GROUPCLASS50", CouponType.CASH, new BigDecimal("50"), new BigDecimal("400"),
                        CouponApplicableType.COURSE, "團體/預約課程專用，滿400折50！"),
                new CouponSeed("專業啞鈴器材優惠", "DUMBBELL90", CouponType.PERCENT, new BigDecimal("10"),
                        new BigDecimal("800"), CouponApplicableType.PRODUCT, "商城器材類別專屬，滿800享9折優惠！"),
                new CouponSeed("年度會籍大特惠", "ANNUAL90", CouponType.PERCENT, new BigDecimal("10"), new BigDecimal("10000"),
                        CouponApplicableType.SUBSCRIPTION, "訂閱年費方案專用，滿10000享9折優惠！"),
                new CouponSeed("私教課程特別折扣", "PTVIP200", CouponType.CASH, new BigDecimal("200"), new BigDecimal("2000"),
                        CouponApplicableType.COURSE, "一對一私人教練課專屬，滿2000折200！"),
                new CouponSeed("運動配件大禮包", "ACC80", CouponType.PERCENT, new BigDecimal("20"), new BigDecimal("300"),
                        CouponApplicableType.PRODUCT, "商城配件類別專用，滿300享8折優惠！"),
                new CouponSeed("瑜珈專屬回饋券", "YOGA120", CouponType.CASH, new BigDecimal("120"), new BigDecimal("1200"),
                        CouponApplicableType.COURSE, "瑜珈伸展課程專屬，滿1200折120！"),
                new CouponSeed("乳清蛋白專屬優惠", "WHEY50", CouponType.CASH, new BigDecimal("50"), new BigDecimal("500"),
                        CouponApplicableType.PRODUCT, "補給品分類專屬，滿500折50！"),
                new CouponSeed("季費會籍專屬特惠", "SEASON300", CouponType.CASH, new BigDecimal("300"), new BigDecimal("4000"),
                        CouponApplicableType.SUBSCRIPTION, "季費會籍方案專屬，滿4000折300！"),
                new CouponSeed("核心穩定課程優惠", "CORE85", CouponType.PERCENT, new BigDecimal("15"), new BigDecimal("1500"),
                        CouponApplicableType.COURSE, "核心肌群訓練課程專用，滿1500享85折！"),
                new CouponSeed("健身拉力帶專屬折扣", "STRAP88", CouponType.PERCENT, new BigDecimal("12"), new BigDecimal("400"),
                        CouponApplicableType.PRODUCT, "配件商品專用，滿400享88折！"),
                new CouponSeed("全館慶典狂歡券", "CELEBRATE200", CouponType.CASH, new BigDecimal("200"),
                        new BigDecimal("2000"), CouponApplicableType.ALL, "全館不限商品/課程/訂閱，滿2000折200！"),
                new CouponSeed("新會員首購好禮", "FIRSTFREE", CouponType.PERCENT, new BigDecimal("5"), new BigDecimal("100"),
                        CouponApplicableType.ALL, "新朋友體驗好禮，滿100享95折優惠！"),
                new CouponSeed("重磅壺鈴系列折價", "KETTLEBELL150", CouponType.CASH, new BigDecimal("150"),
                        new BigDecimal("1500"), CouponApplicableType.PRODUCT, "商城器材類別專屬，滿1500折150！"),
                new CouponSeed("雙人同行會籍優惠", "DOUBLE95", CouponType.PERCENT, new BigDecimal("5"), new BigDecimal("3000"),
                        CouponApplicableType.SUBSCRIPTION, "會籍方案專屬，滿3000享95折！"));

        int created = 0;
        for (CouponSeed seed : seeds) {
            if (couponRepository.findByCode(seed.code()).isPresent()) {
                continue;
            }
            Coupon coupon = new Coupon();
            coupon.setName(seed.name());
            coupon.setCode(seed.code());
            coupon.setType(seed.type());
            coupon.setValue(seed.value());
            coupon.setMinSpend(seed.minSpend());
            coupon.setApplicableType(seed.applicableType());
            coupon.setDescription(seed.description());
            coupon.setStatus(CouponStatus.ACTIVE);
            coupon.setTotalQuantity(1000); // 限量 1000 張
            coupon.setClaimedQuantity(0);
            coupon.setStartDate(LocalDate.now());
            coupon.setEndDate(LocalDate.now().plusYears(1));
            couponRepository.save(coupon);
            created++;
        }
        if (created > 0) {
            log.info("已建立 {} 筆測試優惠券（總共 17 項，分屬產品、課程、會籍、全館）", created);
        }
    }
}
