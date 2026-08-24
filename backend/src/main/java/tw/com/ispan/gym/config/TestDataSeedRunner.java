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
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.coach.GroupClassSession;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;
import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDateTime;

/**
 * 開發用測試帳號播種：讓每位組員的本機資料庫收斂到同一組已知帳號，
 * 前端登入頁的測試帳號面板（LoginView.vue 的 TEST_ACCOUNTS）即以此為準。
 *
 * <p>四組固定帳號（密碼一律 {@value #TEST_PASSWORD}）：
 * <ul>
 * <li>wendy-test@local.test —— 純會員</li>
 * <li>emp-admin@local.test —— 會員＋管理員（雙重身分）</li>
 * <li>admin-only@local.test —— 純管理員，無會員身分（管理端畫面較乾淨）</li>
 * <li>emp-trainer@local.test —— 會員＋員工＋教練（三重身分，適合展示角色疊加）</li>
 * </ul>
 *
 * <p>與 {@link AdminSeedRunner} 的關鍵差異：AdminSeed 遇到不一致會報錯拒絕自動修復
 * （始祖帳號屬安全敏感，不可自動升權）；本 runner 的目的正是「統一各機器的測試狀態」，
 * 因此對既有帳號採<b>自動修復</b>——密碼不符就重設、身分列缺漏就補建、狀態不對就導正，
 * 每項修復都記 log。修復範圍僅限上述固定 email，不會碰其他帳號。
 *
 * <p>冪等：每次啟動都跑，狀態已正確時不寫入任何資料。
 * {@code @Order(2)} 排在 {@link SchemaFixupRunner}（0）與 {@link AdminSeedRunner}（1）之後。
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
    private final GroupClassSessionRepository groupClassSessions;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final PaymentTransactionRepository paymentTransactions;
    private final MemberSubscriptionRepository memberSubscriptions;
    private final MemberCourseQuotaRepository memberCourseQuotas;
    private final tw.com.ispan.gym.repository.course.PrivateCourseRepository privateCourses;
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
            GroupClassSessionRepository groupClassSessions,
            OrderRepository orders,
            OrderItemRepository orderItems,
            PaymentTransactionRepository paymentTransactions,
            MemberSubscriptionRepository memberSubscriptions,
            MemberCourseQuotaRepository memberCourseQuotas,
            tw.com.ispan.gym.repository.course.PrivateCourseRepository privateCourses,
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
        this.groupClassSessions = groupClassSessions;
        this.orders = orders;
        this.orderItems = orderItems;
        this.paymentTransactions = paymentTransactions;
        this.memberSubscriptions = memberSubscriptions;
        this.memberCourseQuotas = memberCourseQuotas;
        this.privateCourses = privateCourses;
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
        Member wendyMember = ensureMember(wendy, "斐凝", "測試", "0911111111");

        Account admin = ensureAccount("emp-admin@local.test", AccountType.MEMBER, AccountType.EMPLOYEE);
        ensureMember(admin, "管理", "測試", "0922222222");
        ensureEmployee(admin, "管理", "測試", "0922222222", EmployeeRole.ADMIN);

        // 純管理員：刻意不建 member 列，roles 只有 ["EMPLOYEE","ADMIN"]。
        // emp-admin 帶著會員身分（適合展示角色疊加），但那也讓 NavBar 同時出現會員與管理
        // 兩個工作區；要呈現乾淨的管理端畫面時用這組，不必動用 AdminSeedRunner 的始祖帳號。
        Account adminOnly = ensureAccount("admin-only@local.test", AccountType.EMPLOYEE);
        ensureEmployee(adminOnly, "純管", "測試", "0944444444", EmployeeRole.ADMIN);

        Account coach = ensureAccount("emp-trainer@local.test", AccountType.MEMBER, AccountType.EMPLOYEE);
        ensureMember(coach, "教練", "測試", "0933333333");
        Employee coachEmployee = ensureEmployee(coach, "教練", "測試", "0933333333", EmployeeRole.EMPLOYEE);
        Trainer coachTrainer = ensureTrainer(coachEmployee);

        ensureCatalogData(coachEmployee);

        // 預約資格要等目錄資料就緒（訂閱要指向方案、堂數要指向教練）。
        // 放在種子最後而不是併進 ensureCatalogData：目錄資料是「大家看的展示資料」，
        // 這兩樣是「wendy 這個帳號的個人狀態」，語意不同。
        ensureActiveSubscription(wendyMember);
        ensureCourseQuota(wendyMember, coachTrainer);

        log.info("測試帳號播種完成（密碼一律 {}；正式環境請以 SEED_TEST_DATA_ENABLED=false 停用）", TEST_PASSWORD);
    }

    /** 帳號不存在就建立；存在則導正密碼與角色集合 */
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

    /** 會員列不存在就建立；存在則導正為 ACTIVE、未刪除 */
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
        if (member.getStatus() != UserStatus.ACTIVE || member.getDeletedAt() != null) {
            member.setStatus(UserStatus.ACTIVE);
            member.setDeletedAt(null);
            member = members.save(member);
            log.info("已將測試會員 {} 導正為 ACTIVE", account.getEmail());
        }
        return member;
    }

    /** 員工列不存在就建立；存在則導正為 ACTIVE、指定職級、未刪除 */
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
        if (changed) {
            employee = employees.save(employee);
            log.info("已將測試員工 {} 導正為 ACTIVE／{}", account.getEmail(), role);
        }
        return employee;
    }

    /** 教練列不存在就建立；存在則導正為未刪除（status 不動，UNAVAILABLE 是合法展示情境） */
    private Trainer ensureTrainer(Employee employee) {
        Trainer trainer = trainers.findByEmployeeEmployeeId(employee.getEmployeeId()).orElse(null);
        if (trainer == null) {
            trainer = new Trainer();
            trainer.setEmployee(employee);
            // populate required personal info from employee/account to satisfy non-null DB constraints
            trainer.setFirstName(employee.getFirstName());
            trainer.setLastName(employee.getLastName());
            trainer.setEmail(employee.getAccount().getEmail());
            trainer.setSpecialty("重量訓練、體態雕塑");
            trainer.setExperience("五年一對一私人教練經驗");
            trainer.setBio("測試用教練帳號，供本機開發與功能展示。");
            log.info("已建立測試教練（employee={}）", employee.getEmployeeId());
            return trainers.save(trainer);
        }
        if (trainer.getDeletedAt() != null) {
            trainer.setDeletedAt(null);
            trainer = trainers.save(trainer);
            log.info("已將測試教練（employee={}）導正為未刪除", employee.getEmployeeId());
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
     * <p>之所以連訂單一起建：member_subscription.order_id 是 NOT NULL。也因為這條 FK，
     * orders 主鍵型別遷移（PreSchemaFixupRunner）會把訂閱整列刪除——所以這個方法每次啟動
     * 都會重查重補，遷移清掉後下次啟動自動長回來。
     *
     * <p>訂單比照 ensureSampleOrders 的規矩：走正式建構子＋markPaid()，並補 PaymentTransaction
     *（退款後台以它為起點，缺了會 404）；以固定 order_no 冪等，不重複建單。
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
        if (!memberCourseQuotas.findEligibleQuota(member.getId(), trainer.getTrainerId()).isEmpty()) {
            return;
        }

        MemberCourseQuota quota = new MemberCourseQuota();
        quota.setMember(member);
        quota.setTrainer(trainer);
        quota.setCourseName("測試用私教堂數");
        quota.setRemainingCourse(10);
        quota.setReservedCourse(0);
        quota.setUsedCourse(0);
        quota.setExpiryDate(LocalDateTime.now().plusMonths(6));
        memberCourseQuotas.save(quota);
        log.info("已為測試會員 {} 播種私教堂數 10 堂（教練 id={}）",
                member.getAccount().getEmail(), trainer.getTrainerId());
    }

    // ─────────────── 目錄資料：讓前端頁面有東西可顯示 ───────────────

    /**
     * 商品／訂閱方案／團課等前台頁面若沒有資料，畫面只會顯示空狀態，
     * 無從判斷版面與串接是否正確。這裡補上最小一組可展示的資料。
     *
     * <p>判斷方式一律是「該表為空才建立」而非逐筆比對——這些是展示用資料，
     * 組員自己在後台新增的內容不該被本 runner 覆蓋或刪除。
     */
    private void ensureCatalogData(Employee coachEmployee) {
        ProductCategory supplementCategory = ensureProductCategory();
        ensureProducts(supplementCategory);
        ensureSubscriptionPlans();
        List<CourseCategory> categories = ensureCourseCategories();
        ensureGroupCourseAndSession(coachEmployee, categories);
        ensurePrivateCourses(coachEmployee, categories);
        ensureSampleOrders();
    }

    private void ensurePrivateCourses(Employee coachEmployee, List<CourseCategory> categories) {
        Trainer trainer = trainers.findByEmployeeEmployeeId(coachEmployee.getEmployeeId()).orElse(null);
        if (trainer == null) {
            log.warn("找不到測試教練，略過私教課播種");
            return;
        }

        List<String> names = List.of(
            "一對一私人重量訓練課", "泰拳與防身格鬥私教課", "高齡伸展與防跌肌力課",
            "增肌減脂客製化體態管理", "孕婦孕產期安全健體課", "運動按摩與物理放鬆私教"
        );
        List<BigDecimal> prices = List.of(
            new BigDecimal("1600"), new BigDecimal("1800"), new BigDecimal("1400"),
            new BigDecimal("1700"), new BigDecimal("1500"), new BigDecimal("1650")
        );
        List<String> descs = List.of(
            "針對個人體態目標，提供專屬重訓計畫，涵蓋動作矯正、爆發力與核心控制。",
            "專業格鬥教練指導，從出拳、防守到實戰反應，大幅提升心肺與自衛能力。",
            "專為銀髮族規劃，低衝擊、著重平衡感、下肢肌力以及關節活動度訓練。",
            "結合運動處方與每日飲食監督，突破體態停滯期，精準降低體脂肪率。",
            "國際孕產證照教練指導，舒緩孕期不適、強化骨盆底肌，為順利生產做準備。",
            "訓練後的肌筋膜釋放與伸展放鬆，解決長期肌肉痠痛，加速身體機能恢復。"
        );

        // 分類映射對照
        // 一對一私人重量訓練課 -> 力量訓練 (index 0)
        // 泰拳與防身格鬥私教課 -> 核心雕塑 (index 3)
        // 高齡伸展與防跌肌力課 -> 核心雕塑 (index 3)
        // 增肌減脂客製化體態管理 -> 核心雕塑 (index 3)
        // 孕婦孕產期安全健體課 -> 身心瑜珈 (index 1)
        // 運動按摩與物理放鬆私教 -> 身心瑜珈 (index 1)
        List<Integer> categoryIndices = List.of(0, 3, 3, 3, 1, 1);

        for (int i = 0; i < names.size(); i++) {
            final String pcName = names.get(i);
            final int categoryIndex = categoryIndices.get(i);
            CourseCategory mappedCategory = categories.stream()
                .filter(c -> c.getName().equals(getCategoryNameByIndex(categoryIndex)))
                .findFirst()
                .orElse(categories.get(0));

            java.util.Optional<tw.com.ispan.gym.entity.course.PrivateCourse> existingOpt = privateCourses.findAll().stream()
                .filter(p -> pcName.equals(p.getName()))
                .findFirst();

            if (existingOpt.isPresent()) {
                tw.com.ispan.gym.entity.course.PrivateCourse pc = existingOpt.get();
                pc.setCategory(mappedCategory);
                privateCourses.save(pc);
                continue;
            }

            tw.com.ispan.gym.entity.course.PrivateCourse pc = new tw.com.ispan.gym.entity.course.PrivateCourse();
            pc.setTrainer(trainer);
            pc.setName(pcName);
            pc.setDescription(descs.get(i));
            pc.setCategory(mappedCategory);
            pc.setPrice(prices.get(i));
            pc.setSessionPerUnit(10);
            pc.setMaxCapacity(1);
            pc.setStatus(tw.com.ispan.gym.enums.course.CourseStatus.ACTIVE);
            privateCourses.save(pc);
        }
        log.info("已建立多個測試私教課方案");
    }

    /**
     * 商品訂單樣本：讓營運統計頁的排行、每日營收趨勢與狀態分佈有足夠資料可看，
     * 只有三兩筆時圖表幾乎沒有意義。
     *
     * <p>以 order_no 前綴 {@value #SAMPLE_ORDER_PREFIX} 辨識是否已播種，補齊到固定筆數即停手，
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
        record Seed(int daysAgo, OrderStatus status, int productIndex, int quantity) {}
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
                default -> { /* UNPAID 是建構子的預設狀態，不必轉移 */ }
            }
            tw.com.ispan.gym.entity.order.Order savedOrder = orders.save(order);
            orderItems.save(new OrderItem(savedOrder, product, product.getName(), unit,
                    seed.quantity()));

            // 付款過的訂單要一併補 PaymentTransaction：退款流程是以它為起點
            //（OrderService.requestRefund 找不到付款紀錄會回 404「找不到這筆訂單的付款紀錄」），
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
            jdbc.update("UPDATE orders SET created_at = ?, updated_at = ? WHERE id = ?",
                    placedAt, placedAt, savedOrder.getId());
            jdbc.update("UPDATE order_item SET created_at = ? WHERE order_id = ?",
                    placedAt, savedOrder.getId());
            if (savedOrder.getPaidAt() != null) {
                jdbc.update("UPDATE orders SET paid_at = ? WHERE id = ?", placedAt, savedOrder.getId());
            }
            created++;
        }
        if (created > 0) {
            log.info("已建立 {} 筆測試訂單（狀態涵蓋 PAID／UNPAID／REFUNDED／CANCELLED）", created);
        }
    }

    private ProductCategory ensureProductCategory() {
        return productCategories.findAll().stream().findFirst().orElseGet(() -> {
            ProductCategory category = new ProductCategory();
            category.setName("運動補給");
            ProductCategory saved = productCategories.save(category);
            log.info("已建立測試商品分類：{}", saved.getName());
            return saved;
        });
    }

    /**
     * 逐筆以名稱比對補建，不是「整張表空的才播種」——早期資料庫已經有一兩筆手動建立的
     * 商品，用整表判斷會讓這批種子永遠不會進去，商城與報表都只有零星資料可看。
     */
    private void ensureProducts(ProductCategory category) {
        record Seed(String name, String price, int stock, String brand, String flavor, String desc) {}
        List<Seed> seeds = List.of(
                new Seed("乳清蛋白 1kg", "1280", 50, "MyProtein", "巧克力", "每份 24g 蛋白質，訓練後補給首選。"),
                new Seed("BCAA 支鏈胺基酸", "890", 30, "Optimum", "莓果", "訓練中補充，延緩疲勞。"),
                new Seed("搖搖杯 700ml", "250", 100, "Blender", null, "防漏設計，附攪拌球。"),
                new Seed("健身手套", "450", 20, "Harbinger", null, "止滑耐磨，保護掌心。"),
                new Seed("能量棒（12入）", "600", 0, "Quest", "花生", "高蛋白低糖，隨身補給。"),
                new Seed("肌酸 300g", "780", 40, "Optimum", null, "提升爆發力與訓練表現。"),
                new Seed("彈力帶組（5條）", "520", 60, "TheraBand", null, "五種阻力，適合暖身與居家訓練。"),
                new Seed("運動毛巾", "180", 120, "Willy", null, "吸汗快乾，健身房必備。"),
                new Seed("瑜珈墊 6mm", "990", 25, "Manduka", null, "止滑防潑水，附收納背帶。"),
                new Seed("護腕（一對）", "320", 45, "Harbinger", null, "大重量訓練穩定腕關節。"));

        int created = 0;
        for (Seed seed : seeds) {
            if (products.findAll().stream().anyMatch(p -> seed.name().equals(p.getName()))) {
                continue;
            }
            Product product = new Product();
            product.setName(seed.name());
            product.setPrice(new BigDecimal(seed.price()));
            product.setStock(seed.stock());
            product.setBrand(seed.brand());
            product.setFlavor(seed.flavor());
            product.setDescription(seed.desc());
            product.setProductCategory(category);
            products.save(product);
            created++;
        }
        if (created > 0) {
            // 其中一筆刻意庫存為 0，方便驗證前端的「已售完」呈現
            log.info("已建立 {} 筆測試商品（含 1 筆零庫存）", created);
        }
    }

    private void ensureSubscriptionPlans() {
        if (!subscriptionPlans.findAll().isEmpty()) {
            return;
        }
        record Seed(String name, String price, int days, String desc) {}
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

    private List<CourseCategory> ensureCourseCategories() {
        List<String> categoryNames = List.of("力量訓練", "身心瑜珈", "動感飛輪", "核心雕塑");
        for (int i = 0; i < categoryNames.size(); i++) {
            final String name = categoryNames.get(i);
            boolean exists = courseCategories.findAll().stream().anyMatch(c -> name.equals(c.getName()));
            if (!exists) {
                CourseCategory category = new CourseCategory();
                category.setName(name);
                category.setSortOrder(i + 1);
                category.setStatus(tw.com.ispan.gym.enums.common.CategoryStatus.ACTIVE);
                courseCategories.save(category);
                log.info("已建立測試課程分類：{}", name);
            }
        }
        
        // 清理 DB 中舊的 "團體有氧" 分類
        courseCategories.findAll().stream()
            .filter(c -> "團體有氧".equals(c.getName()))
            .findFirst()
            .ifPresent(oldCat -> {
                // 將所有關聯至 "團體有氧" 的團課與私教課重新指派給新的分類
                CourseCategory defaultCat = courseCategories.findAll().stream()
                    .filter(c -> categoryNames.contains(c.getName()))
                    .findFirst().orElse(null);
                
                if (defaultCat != null) {
                    groupCourses.findAll().stream()
                        .filter(gc -> gc.getCategory() != null && oldCat.getCourseCategoryId().equals(gc.getCategory().getCourseCategoryId()))
                        .forEach(gc -> {
                            gc.setCategory(defaultCat);
                            groupCourses.save(gc);
                        });
                    privateCourses.findAll().stream()
                        .filter(pc -> pc.getCategory() != null && oldCat.getCourseCategoryId().equals(pc.getCategory().getCourseCategoryId()))
                        .forEach(pc -> {
                            pc.setCategory(defaultCat);
                            privateCourses.save(pc);
                        });
                }
                courseCategories.delete(oldCat);
                log.info("已清理歷史的 '團體有氧' 分類");
            });

        return courseCategories.findAll().stream()
            .filter(c -> categoryNames.contains(c.getName()))
            .toList();
    }

    /**
     * 團課主檔與場次一起建立：只有主檔而沒有場次時，前台團課頁仍是空的
     * （那頁顯示的是「即將開始且開放報名的場次」，不是課程本身）。
     * 場次時間刻意設在未來，才會出現在前台列表。
     */
    private void ensureGroupCourseAndSession(Employee coachEmployee, List<CourseCategory> categories) {
        Trainer trainer = trainers.findByEmployeeEmployeeId(coachEmployee.getEmployeeId()).orElse(null);
        if (trainer == null) {
            log.warn("找不到測試教練，略過團課播種");
            return;
        }

        List<String> names = List.of(
            "燃脂有氧", "核心能量瑜珈", "動感飛輪衝刺", 
            "TRX 懸吊全身雕塑", "高強度拳擊有氧", "活力槓鈴有氧"
        );
        List<String> descs = List.of(
            "高強度間歇訓練，適合想快速燃脂的學員。", "強化深層核心肌群，改善體態並舒緩緊繃筋骨。", "模擬戶外起伏爬坡，著重爆發力與心肺提升。",
            "利用自身體重阻力訓練，深層鍛鍊肌耐力。", "結合拳擊步伐與出拳動作，極速爆汗舒壓。", "經典槓鈴負重配合音樂節奏，燃脂並雕塑肌肉線條。"
        );

        // 分類映射對照
        // 燃脂有氧 -> 核心雕塑 (index 3)
        // 核心能量瑜珈 -> 身心瑜珈 (index 1)
        // 動感飛輪衝刺 -> 動感飛輪 (index 2)
        // TRX -> 力量訓練 (index 0)
        // 高強度拳擊有氧 -> 核心雕塑 (index 3)
        // 活力槓鈴有氧 -> 力量訓練 (index 0)
        List<Integer> categoryIndices = List.of(3, 1, 2, 0, 3, 0);

        for (int i = 0; i < names.size(); i++) {
            final String gcName = names.get(i);
            final int categoryIndex = categoryIndices.get(i);
            CourseCategory mappedCategory = categories.stream()
                .filter(c -> c.getName().equals(getCategoryNameByIndex(categoryIndex)))
                .findFirst()
                .orElse(categories.get(0));

            java.util.Optional<GroupCourse> existingOpt = groupCourses.findAll().stream()
                .filter(g -> gcName.equals(g.getName()))
                .findFirst();

            if (existingOpt.isPresent()) {
                GroupCourse gc = existingOpt.get();
                gc.setCategory(mappedCategory);
                groupCourses.save(gc);
                continue;
            }

            GroupCourse course = new GroupCourse();
            course.setTrainer(trainer);
            course.setName(gcName);
            course.setDescription(descs.get(i));
            course.setCategory(mappedCategory);
            course.setMaxCapacity(20);
            GroupCourse savedCourse = groupCourses.save(course);

            LocalDateTime start = LocalDateTime.now().plusDays(3 + i).withHour(19).withMinute(0)
                    .withSecond(0).withNano(0);
            GroupClassSession session = new GroupClassSession();
            session.setCourse(savedCourse);
            session.setRoomName("A 教室");
            session.setStartAt(start);
            session.setEndAt(start.plusHours(1));
            session.setCapacity(20);
            session.setStatus(GroupClassSessionStatus.SCHEDULED);
            groupClassSessions.save(session);
        }
        log.info("已建立多個測試團課主檔與未來場次");
    }

    private String getCategoryNameByIndex(int index) {
        List<String> names = List.of("力量訓練", "身心瑜珈", "動感飛輪", "核心雕塑");
        return names.get(index);
    }
}
