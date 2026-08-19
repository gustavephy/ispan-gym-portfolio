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
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
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
        ensureMember(wendy, "斐凝", "測試", "0911111111");

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
        ensureSampleOrders();
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
