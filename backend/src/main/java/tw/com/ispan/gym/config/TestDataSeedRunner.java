package tw.com.ispan.gym.config;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
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
 * <p>三組固定帳號（密碼一律 {@value #TEST_PASSWORD}）：
 * <ul>
 * <li>wendy-test@local.test —— 純會員</li>
 * <li>emp-admin@local.test —— 會員＋管理員（雙重身分）</li>
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
        ensureMember(wendy, "斐凝", "測試", "0911111111");

        Account admin = ensureAccount("emp-admin@local.test", AccountType.MEMBER, AccountType.EMPLOYEE);
        ensureMember(admin, "管理", "測試", "0922222222");
        ensureEmployee(admin, "管理", "測試", "0922222222", EmployeeRole.ADMIN);

        Account coach = ensureAccount("emp-trainer@local.test", AccountType.MEMBER, AccountType.EMPLOYEE);
        ensureMember(coach, "教練", "測試", "0933333333");
        Employee coachEmployee = ensureEmployee(coach, "教練", "測試", "0933333333", EmployeeRole.EMPLOYEE);
        ensureTrainer(coachEmployee);

        ensureCatalogData(coachEmployee);

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
    private void ensureMember(Account account, String firstName, String lastName, String phone) {
        Member member = members.findByAccountId(account.getId()).orElse(null);
        if (member == null) {
            member = new Member();
            member.setAccount(account);
            member.setFirstName(firstName);
            member.setLastName(lastName);
            member.setPhone(phone);
            member.setStatus(UserStatus.ACTIVE);
            members.save(member);
            log.info("已建立測試會員 {}", account.getEmail());
            return;
        }
        if (member.getStatus() != UserStatus.ACTIVE || member.getDeletedAt() != null) {
            member.setStatus(UserStatus.ACTIVE);
            member.setDeletedAt(null);
            members.save(member);
            log.info("已將測試會員 {} 導正為 ACTIVE", account.getEmail());
        }
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
    private void ensureTrainer(Employee employee) {
        Trainer trainer = trainers.findByEmployeeId(employee.getId()).orElse(null);
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
            trainers.save(trainer);
            log.info("已建立測試教練（employee={}）", employee.getId());
            return;
        }
        if (trainer.getDeletedAt() != null) {
            trainer.setDeletedAt(null);
            trainers.save(trainer);
            log.info("已將測試教練（employee={}）導正為未刪除", employee.getId());
        }
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
        CourseCategory groupCategory = ensureCourseCategory();
        ensureGroupCourseAndSession(coachEmployee, groupCategory);
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

    private void ensureProducts(ProductCategory category) {
        if (!products.findAll().isEmpty()) {
            return;
        }
        record Seed(String name, String price, int stock, String brand, String flavor, String desc) {}
        List<Seed> seeds = List.of(
                new Seed("乳清蛋白 1kg", "1280", 50, "MyProtein", "巧克力", "每份 24g 蛋白質，訓練後補給首選。"),
                new Seed("BCAA 支鏈胺基酸", "890", 30, "Optimum", "莓果", "訓練中補充，延緩疲勞。"),
                new Seed("搖搖杯 700ml", "250", 100, "Blender", null, "防漏設計，附攪拌球。"),
                new Seed("健身手套", "450", 20, "Harbinger", null, "止滑耐磨，保護掌心。"),
                new Seed("能量棒（12入）", "600", 0, "Quest", "花生", "高蛋白低糖，隨身補給。"));
        for (Seed seed : seeds) {
            Product product = new Product();
            product.setName(seed.name());
            product.setPrice(new BigDecimal(seed.price()));
            product.setStock(seed.stock());
            product.setBrand(seed.brand());
            product.setFlavor(seed.flavor());
            product.setDescription(seed.desc());
            product.setProductCategory(category);
            products.save(product);
        }
        // 其中一筆刻意庫存為 0，方便驗證前端的「已售完」呈現
        log.info("已建立 {} 筆測試商品（含 1 筆零庫存）", seeds.size());
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

    private CourseCategory ensureCourseCategory() {
        return courseCategories.findAll().stream().findFirst().orElseGet(() -> {
            CourseCategory category = new CourseCategory();
            category.setName("團體有氧");
            CourseCategory saved = courseCategories.save(category);
            log.info("已建立測試課程分類：{}", saved.getName());
            return saved;
        });
    }

    /**
     * 團課主檔與場次一起建立：只有主檔而沒有場次時，前台團課頁仍是空的
     * （那頁顯示的是「即將開始且開放報名的場次」，不是課程本身）。
     * 場次時間刻意設在未來，才會出現在前台列表。
     */
    private void ensureGroupCourseAndSession(Employee coachEmployee, CourseCategory category) {
        if (!groupCourses.findAll().isEmpty()) {
            return;
        }
        Trainer trainer = trainers.findByEmployeeId(coachEmployee.getId()).orElse(null);
        if (trainer == null) {
            log.warn("找不到測試教練，略過團課播種");
            return;
        }
        GroupCourse course = new GroupCourse();
        course.setTrainer(trainer);
        course.setName("燃脂有氧");
        course.setDescription("高強度間歇訓練，適合想快速燃脂的學員。");
        course.setCategory(category);
        course.setMaxCapacity(20);
        GroupCourse savedCourse = groupCourses.save(course);

        LocalDateTime start = LocalDateTime.now().plusDays(3).withHour(19).withMinute(0)
                .withSecond(0).withNano(0);
        GroupClassSession session = new GroupClassSession();
        session.setCourse(savedCourse);
        session.setRoomName("A 教室");
        session.setStartAt(start);
        session.setEndAt(start.plusHours(1));
        session.setCapacity(20);
        session.setStatus(GroupClassSessionStatus.SCHEDULED);
        groupClassSessions.save(session);
        log.info("已建立測試團課主檔與 1 筆未來場次（{}）", start);
    }
}
