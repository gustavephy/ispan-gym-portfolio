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
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;
import tw.com.ispan.gym.repository.coach.CoachSlotRepository;
import tw.com.ispan.gym.entity.coach.CoachSlot;
import tw.com.ispan.gym.enums.coach.SlotStatus;
import tw.com.ispan.gym.repository.member.MemberSubscriptionRepository;
import tw.com.ispan.gym.repository.coach.MemberCourseQuotaRepository;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.entity.coach.MemberCourseQuota;
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
import tw.com.ispan.gym.entity.coach.CoachBooking;
import tw.com.ispan.gym.entity.coach.GroupClassBooking;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;
import tw.com.ispan.gym.enums.coach.GroupClassBookingStatus;
import tw.com.ispan.gym.enums.member.Gender;
import tw.com.ispan.gym.repository.coach.CoachBookingRepository;
import tw.com.ispan.gym.repository.coach.GroupClassBookingRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

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

    /**
     * 測試帳號（wendy、emp-admin、emp-trainer 1–12）的 member.created_at 回填到幾天前。
     * 不回填的話它們是「播種當下」建立的，重建資料庫那天營運統計的新會員會憑空多 14 位；
     * 120 天落在所有近期區間（近 30／90 天）之外，也不會混進擬真會員的成長曲線。
     */
    private static final int TEST_MEMBER_BACKDATE_DAYS = 120;
    private static final int SAMPLE_ORDER_COUNT = 10;
    /** 種子私教課的課名；ensureCatalogData 用它辨識哪些私教課是種子建的、哪些是後台手動新增的 */
    private static final List<String> SEED_PRIVATE_COURSE_NAMES = List.of(
            "一對一重訓體驗課", "重訓入門計畫（5堂）", "重訓進階計畫（10堂）");

    /**
     * 種子圖片：全組共用的 Cloudinary 帳號（cloud mksmqzij）上既有的資源，public_id 是檔案 MD5。
     * 圖檔 2026-08-30 就從後台上傳過，但「哪張圖對哪個商品」只存在上傳者本機的資料庫，
     * 重建資料庫就全部消失；把對應寫進種子，任何人重建或首次啟動都會有圖。
     * 交付網址不需要金鑰，沒設 CLOUDINARY_* 的組員一樣看得到。原檔為 avif 的改用 .jpg 副檔名交付
     * （Cloudinary 依副檔名轉格式），避免舊瀏覽器不支援。
     * 既有資料只在圖片為空時回填，不覆蓋後台自行上傳的圖。
     */
    private static final String CLOUD_IMG_BASE = "https://res.cloudinary.com/mksmqzij/image/upload/";

    private static String cloudImg(String publicIdWithExt) {
        return CLOUD_IMG_BASE + publicIdWithExt;
    }

    /** 商品名稱 → 圖片；46 筆與 ensure46Products 的清單一一對應 */
    private static final java.util.Map<String, String> SEED_PRODUCT_IMAGES = java.util.Map.ofEntries(
            // 補給品
            java.util.Map.entry("分離乳清蛋白-巧克力", cloudImg("223916c7013b4fbfa7d269535fcd5806.webp")),
            java.util.Map.entry("分離乳清蛋白-香草", cloudImg("174ac8166d0379b57ef3a4497c5ac852.webp")),
            java.util.Map.entry("植物性蛋白粉-草莓", cloudImg("294f0c0a75dd4e64a5bf9a4969a1bf56.webp")),
            java.util.Map.entry("微粉化肌酸", cloudImg("c66decdb11070319aea0665eb116fbf0.webp")),
            java.util.Map.entry("發泡錠-維生素C", cloudImg("16d08f76b307e3d229dfc5476f2cb540.webp")),
            java.util.Map.entry("電解質補給粉-檸檬", cloudImg("4f35696c2d716b21520a6e8318f487ce.webp")),
            java.util.Map.entry("左旋肉鹼液", cloudImg("ac489f57a4eee03bc51400f91228ad2e.jpg")),
            java.util.Map.entry("高效氮泵前訓練-西瓜", cloudImg("ce7aca75266a470e02d091322443ae2a.jpg")),
            java.util.Map.entry("綜合維他命加強錠", cloudImg("150f156d0d1696699c1ddf528f51e511.jpg")),
            java.util.Map.entry("鋅鎂膠囊(ZMA)", cloudImg("d4eeba29502ad49f7c8a55fc055f91bc.webp")),
            java.util.Map.entry("魚油軟膠囊", cloudImg("4a99e8460f4981c1dd015e552f1ed4fe.webp")),
            java.util.Map.entry("高蛋白能量棒-花生", cloudImg("07216050141bfae7cc2037b4f3f4e646.webp")),
            java.util.Map.entry("高蛋白曲奇餅", cloudImg("ed5d718d9e7a5ad60b722bb2c9cc1661.jpg")),
            java.util.Map.entry("BCAA 3:1:2 膠囊", cloudImg("9a9bf3ed14ff20a5c621bae06cb50587.webp")),
            java.util.Map.entry("穀氨醯胺粉", cloudImg("541065957d11203feefb7c38506315ea.webp")),
            java.util.Map.entry("關節保養葡萄糖胺", cloudImg("635f4056197ea2f7ca2379861fe167e3.webp")),
            // 器材
            java.util.Map.entry("環保瑜珈墊 6mm", cloudImg("3d1da279adc8ff292edf1ed7b0ac9158.webp")),
            java.util.Map.entry("防滑乳膠阻力圈", cloudImg("abab4c345a925efc6f6aba73914a2c2e.jpg")),
            java.util.Map.entry("奧林匹克長槓 2.2m", cloudImg("22c189c0d56f09cfa5338a0ff1b4bcae.webp")),
            java.util.Map.entry("專業環保壺鈴 8kg", cloudImg("13b61bd35bffcdc35700ee10ccd23cec.jpg")),
            java.util.Map.entry("專業環保壺鈴 12kg", cloudImg("305f5a25702d44597878ff6908aa46e5.webp")),
            java.util.Map.entry("專業環保壺鈴 16kg", cloudImg("3fe1b8911d8435adcfe3fb6b5412f749.webp")),
            java.util.Map.entry("六角橡膠啞鈴 5kg", cloudImg("4c34b61a5f08212036e79d764f772478.webp")),
            java.util.Map.entry("六角橡膠啞鈴 10kg", cloudImg("80e56b061d6258b2b44571c3d45cda31.webp")),
            java.util.Map.entry("六角橡膠啞鈴 15kg", cloudImg("aa33611fc2b3afb8f66355c18c637019.jpg")),
            java.util.Map.entry("六角橡膠啞鈴 20kg", cloudImg("b711e45d416e2ada85911ae01ee1bbec.webp")),
            java.util.Map.entry("高密度泡沫按摩滾軸", cloudImg("02d713a3fd62d2ead6a34a30d0887a3c.webp")),
            java.util.Map.entry("加厚阻力帶-拉力繩", cloudImg("0578840247515744601d418c0473fd6e.webp")),
            java.util.Map.entry("多功能實心藥球 4kg", cloudImg("2e5e6dc1c9e4726fa064304a1bc68367.webp")),
            java.util.Map.entry("多功能實心藥球 6kg", cloudImg("1211391728c12544f5957373567114af.webp")),
            java.util.Map.entry("高彈力抗暴瑜珈球", cloudImg("34a5fa7539d2021898fc1a9f010eec9c.webp")),
            // 配件
            java.util.Map.entry("牛皮健身腰帶", cloudImg("c2cedea03b5813db5c66023fe8e7243e.webp")),
            java.util.Map.entry("專業重訓拉力帶", cloudImg("75afc6dea97b488401781319196f85f1.webp")),
            java.util.Map.entry("透氣運動防滑手套", cloudImg("180eaad4c3b757c65c7b7af0ec65af2e.webp")),
            java.util.Map.entry("加壓型針織護膝(一對)", cloudImg("b9fb1a56dc8250bb05e78d0bb8f26dd2.webp")),
            java.util.Map.entry("專業運動防滑粉", cloudImg("748aed176ded7c20db099409d5333864.webp")),
            java.util.Map.entry("高階運動水壺 1000ml", cloudImg("e7b8fcdb1514249951c871071e3b0343.webp")),
            java.util.Map.entry("多功能乾濕分離運動包", cloudImg("26578813fbcda306de3a6e85bf5a0e9b.webp")),
            java.util.Map.entry("超細纖維吸汗運動毛巾", cloudImg("7799cadf60fb935d7b8c570bd342b0a5.webp")),
            java.util.Map.entry("運動排汗止汗頭帶", cloudImg("b14eaf596fb9559e91982f9e0abbcb1a.webp")),
            java.util.Map.entry("便攜式滾輪按摩棒", cloudImg("1f9b742b8d1d58e088c731bbde901f0d.webp")),
            java.util.Map.entry("專業重訓護腕(一對)", cloudImg("9f133847697b1ee22b666d7fb751a5d1.webp")),
            java.util.Map.entry("阻力帶專用收納袋", cloudImg("4fb5bacb3aa06b0e35c66d2824b7d448.webp")),
            java.util.Map.entry("專業鋼絲跳繩", cloudImg("cfb884ead154d8f7f944db27ca14c1c8.jpg")),
            java.util.Map.entry("運動護肘加壓帶(一對)", cloudImg("6726364369d6da80549282e3039217df.webp")),
            java.util.Map.entry("防滑瑜珈鋪巾", cloudImg("ca0e8c173d9cf81bf8aca7bd05378ae4.webp")));

    /** 團課名稱 → 封面；與 ensureGroupCourseAndSession 的 16 筆對應 */
    private static final java.util.Map<String, String> SEED_GROUP_COURSE_IMAGES = java.util.Map.ofEntries(
            java.util.Map.entry("高強度燃脂班", cloudImg("6bf78c2fe9967da6a9f97d10556dccbe.jpg")),
            java.util.Map.entry("舞動有氧", cloudImg("b5f8dc9cdfcc159daab4139a096bc865.jpg")),
            java.util.Map.entry("跳繩體能訓練", cloudImg("5b6ff076e0d1a79d3262d818f792b2bd.webp")),
            java.util.Map.entry("自行車有氧", cloudImg("73d26355b583f30c26278af3984ee501.jpg")),
            java.util.Map.entry("基礎槓鈴訓練", cloudImg("12158c3b2462f2b1e2aada8e5ef63bff.jpg")),
            java.util.Map.entry("壺鈴全身訓練", cloudImg("20714275339cce85fbd629c3b579ef98.jpg")),
            java.util.Map.entry("啞鈴雕塑班", cloudImg("9b09789487aaeec3c55232022bb4115a.jpg")),
            java.util.Map.entry("TRX 懸吊訓練", cloudImg("212c9530475f42424c5ea385390ce913.png")),
            java.util.Map.entry("哈達瑜珈入門", cloudImg("c30311e023df888c90770478c1419f66.jpg")),
            java.util.Map.entry("陰瑜珈深層放鬆", cloudImg("08a7525f6b9c7a7f0a901d51421f6c80.jpg")),
            java.util.Map.entry("流動瑜珈", cloudImg("6d10bd14e48d3d2781c1a20ae67dd708.jpg")),
            java.util.Map.entry("瑜珈伸展舒壓班", cloudImg("db8be5dc41ba52ab6427cba56e4a8dab.png")),
            java.util.Map.entry("體能循環訓練", cloudImg("2e0cf2678ac0615f180f613af03b7b11.jpg")),
            java.util.Map.entry("核心穩定訓練", cloudImg("d3ed63a2b149f908f697777628b3cf55.jpg")),
            java.util.Map.entry("敏捷速度訓練", cloudImg("fd71863859f9840ee84f5baaf29d00a5.webp")),
            java.util.Map.entry("體重訓練入門", cloudImg("3e1d2b8d4a3dbccc56a0771ad3c4ba48.jpg")));

    /** 私教課名稱 → 封面 */
    private static final java.util.Map<String, String> SEED_PRIVATE_COURSE_IMAGES = java.util.Map.of(
            "一對一重訓體驗課", cloudImg("c0d6d1c284ddc82f0067d9eb4920baa2.jpg"),
            "重訓入門計畫（5堂）", cloudImg("f7bc072162fa8171efe1cf54f77acc07.jpg"),
            "重訓進階計畫（10堂）", cloudImg("9b2bd23ceb1e8baba80679884280bcde.jpg"));

    /**
     * 教練帳號 → 頭像。
     */
    private static final java.util.Map<String, String> SEED_TRAINER_AVATARS = java.util.Map.ofEntries(
            java.util.Map.entry("emp-trainer@local.test", cloudImg("e30dbaae9c9bc29dbf8564ce01261a22.jpg")),
            java.util.Map.entry("emp-trainer2@local.test", cloudImg("8476efc7ce8723da2abcaca5226176a2.png")),
            java.util.Map.entry("emp-trainer3@local.test", cloudImg("c8fc47abd2af887e05529534d88e36e2.jpg")),
            java.util.Map.entry("emp-trainer4@local.test", cloudImg("848f2f40321ad9e9f0031589744b43b7.jpg")),
            java.util.Map.entry("emp-trainer5@local.test", cloudImg("de9fdffba4fc1b18ba789d882c91b6d7.jpg")),
            java.util.Map.entry("emp-trainer6@local.test", cloudImg("1b0d5a805cc6a2d80123f602a1dbc1e6.jpg")),
            java.util.Map.entry("emp-trainer7@local.test", cloudImg("381bb229ad65baf5c71eeb0167715163.webp")),
            java.util.Map.entry("emp-trainer8@local.test", cloudImg("03df533da878abbf4e19ef7e97404f8f.jpg")),
            java.util.Map.entry("emp-trainer9@local.test", cloudImg("36062fab8d19a2bd01cef22342023b32.jpg")),
            java.util.Map.entry("emp-trainer10@local.test", cloudImg("20714275339cce85fbd629c3b579ef98.jpg")),
            java.util.Map.entry("emp-trainer11@local.test", cloudImg("6d10bd14e48d3d2781c1a20ae67dd708.jpg")),
            java.util.Map.entry("emp-trainer12@local.test", cloudImg("08a7525f6b9c7a7f0a901d51421f6c80.jpg")));

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

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
    private final CoachSlotRepository coachSlots;
    private final CoachBookingRepository coachBookings;
    private final GroupClassBookingRepository groupClassBookings;
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
            CoachSlotRepository coachSlots,
            CoachBookingRepository coachBookings,
            GroupClassBookingRepository groupClassBookings,
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
        this.coachSlots = coachSlots;
        this.coachBookings = coachBookings;
        this.groupClassBookings = groupClassBookings;
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
        Member wendyMember = ensureMember(wendy, "陳", "美秀", "0911111111");

        Account admin = ensureAccount("emp-admin@local.test", AccountType.MEMBER, AccountType.EMPLOYEE);
        ensureMember(admin, "林", "威力", "0922222222");
        ensureEmployee(admin, "林", "威力", "0922222222", EmployeeRole.ADMIN);

        // 純管理員：刻意不建 member 列，roles 只有 ["EMPLOYEE","ADMIN"]。
        // emp-admin 帶著會員身分（適合展示角色疊加），但那也讓 NavBar 同時出現會員與管理
        // 兩個工作區；要呈現乾淨的管理端畫面時用這組，不必動用 AdminSeedRunner 的始祖帳號。
        Account adminOnly = ensureAccount("admin-only@local.test", AccountType.EMPLOYEE);
        ensureEmployee(adminOnly, "張", "小美", "0944444444", EmployeeRole.ADMIN);

        record TrainerSeed(String email, String lastName, String firstName, String phone, String specialty) {
        }
        List<TrainerSeed> trainerSeeds = List.of(
                new TrainerSeed("emp-trainer@local.test", "林", "志強", "0933333333", "重量訓練、肌力提升"),
                new TrainerSeed("emp-trainer2@local.test", "陳", "麥克", "0933333334", "重量訓練、心肺耐力"),
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
            ensureMember(acc, ts.lastName(), ts.firstName(), ts.phone());
            Employee emp = ensureEmployee(acc, ts.lastName(), ts.firstName(), ts.phone(), EmployeeRole.EMPLOYEE);
            Trainer tr = ensureTrainer(emp, ts.specialty());
            seededTrainers.add(tr);
        }

        ensureCatalogData(seededTrainers);

        // 統計展示資料：擬真會員、更多樣本訂單、過去的團課場次與私教預約，
        // 讓營運統計／營運分析的圖表有可看的分布（只加不刪，全部冪等；
        // 放在 ensureSampleOrderHistory 之前，新訂單才會一併補到狀態時間軸）
        List<Member> demoMembers = ensureRealisticMembers();
        ensureMoreSampleOrders(demoMembers);
        ensureSampleCourseOrders(demoMembers);
        ensurePastGroupClassSessions(demoMembers);
        ensurePastCoachSlotBookings(demoMembers);

        // 預約資格要等目錄資料就緒（訂閱要指向方案、堂數要指向教練）。
        // 放在種子最後而不是併進 ensureCatalogData：目錄資料是「大家看的展示資料」，
        // 這兩樣是「wendy 這個帳號的個人狀態」，語意不同。
        ensureActiveSubscription(wendyMember);
        // ensureCourseQuota(wendyMember, seededTrainers.get(0));

        // 樣本訂單（含上面的訂閱訂單）都是直接建單、沒走 OrderService，補上狀態時間軸
        ensureSampleOrderHistory();

        log.info("測試帳號播種完成（密碼一律 {}；正式環境請以 SEED_TEST_DATA_ENABLED=false 停用）", TEST_PASSWORD);
    }

    /** 帳號不存在就建立；存在則導正為與密碼與角色集合 */
    /**
     * 給 JDBC 回填用：把「JVM 預設時區的牆鐘時間」轉成「UTC 牆鐘時間」再寫進資料庫。
     * Hibernate 因 {@code hibernate.jdbc.time_zone=UTC} 寫入時會做這個轉換、讀出時反向轉回，
     * JdbcTemplate 不會；不轉的話回填值被 Hibernate 讀回來時會多 8 小時（台北），
     * 下午四點以後啟動的播種會整批漂到隔天。JVM 本來就是 UTC 時等於不轉，行為與過去相同。
     */
    private static LocalDateTime toDbUtc(LocalDateTime local) {
        return local.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

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
    private Member ensureMember(Account account, String lastName, String firstName, String phone) {
        Member member = members.findByAccountId(account.getId()).orElse(null);
        if (member == null) {
            member = new Member();
            member.setAccount(account);
            member.setLastName(lastName);
            member.setFirstName(firstName);
            member.setPhone(phone);
            member.setStatus(UserStatus.ACTIVE);
            log.info("已建立測試會員 {}", account.getEmail());
            member = members.save(member);
            // created_at 由 @PrePersist 設成「現在」，比照擬真會員直接下 SQL 回填，理由見 TEST_MEMBER_BACKDATE_DAYS
            LocalDateTime seededAt = toDbUtc(LocalDateTime.now().minusDays(TEST_MEMBER_BACKDATE_DAYS));
            jdbc.update("UPDATE member SET created_at = ?, updated_at = ? WHERE member_id = ?",
                    seededAt, seededAt, member.getId());
            return member;
        }
        boolean changed = false;
        if (member.getStatus() != UserStatus.ACTIVE || member.getDeletedAt() != null) {
            member.setStatus(UserStatus.ACTIVE);
            member.setDeletedAt(null);
            changed = true;
            log.info("已將測試會員 {} 導正為 ACTIVE", account.getEmail());
        }
        if (!firstName.equals(member.getFirstName()) || !lastName.equals(member.getLastName())) {
            member.setLastName(lastName);
            member.setFirstName(firstName);
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
    private Employee ensureEmployee(Account account, String lastName, String firstName,
            String phone, EmployeeRole role) {
        Employee employee = employees.findByAccountId(account.getId()).orElse(null);
        if (employee == null) {
            employee = new Employee();
            employee.setAccount(account);
            employee.setLastName(lastName);
            employee.setFirstName(firstName);
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
            trainer.setProfileImageUrl(SEED_TRAINER_AVATARS.get(employee.getAccount().getEmail()));
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
        // 頭像只回填空值，教練自己上傳過的不動
        String avatar = SEED_TRAINER_AVATARS.get(employee.getAccount().getEmail());
        if (avatar != null && isBlank(trainer.getProfileImageUrl())) {
            trainer.setProfileImageUrl(avatar);
            changed = true;
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
     *
     * <p>
     * 日期比照 {@link #ensureSampleOrders} 用 SQL 回填到訂閱起日（前一天）：不回填的話
     * paid_at 會是後端啟動當下，每次重建資料庫，當天的營運統計就憑空多一筆訂單與營收
     * （2026-09-07 demo 彩排時實際發生）。{@link #ensureSampleOrderHistory} 之後會讀回填後的
     * 日期補時間軸，所以這裡要在它之前完成。
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
        // 訂閱起日往前推一天：閉區間邊界在「剛好現在」的比較上容易剛好差一瞬間，測試資料不玩這種邊緣；
        // 訂單的建立／付款時間也回填成同一刻，才不會落在啟動當天的報表裡
        LocalDateTime startedAt = LocalDateTime.now().minusDays(1);

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
            // created_at 由 @PrePersist 設成「現在」、paid_at 由 markPaid() 設成「現在」，
            // 都回填到訂閱起日，做法同 ensureSampleOrders
            jdbc.update("UPDATE orders SET created_at = ?, updated_at = ?, paid_at = ? WHERE order_id = ?",
                    toDbUtc(startedAt), toDbUtc(startedAt), toDbUtc(startedAt), order.getOrderId());

            PaymentTransaction payment = new PaymentTransaction(
                    order, "ECPAY", SEED_SUBSCRIPTION_ORDER_NO, plan.getPrice(), "TWD");
            payment.setTradeNo(SEED_SUBSCRIPTION_ORDER_NO);
            payment.setPaymentMethod("Credit_CreditCard");
            payment.setStatus("SUCCESS");
            payment.setPaidAt(startedAt);
            payment.setRtnCode(1);
            payment.setRtnMsg("交易成功");
            paymentTransactions.save(payment);
        }

        MemberSubscription subscription = new MemberSubscription();
        subscription.setMember(member);
        subscription.setOrder(order);
        subscription.setSubscriptionPlan(plan);
        subscription.setPlanName(plan.getName());
        subscription.setStartDate(startedAt);
        subscription.setEndDate(LocalDateTime.now().plusMonths(3));
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        MemberSubscription saved = memberSubscriptions.save(subscription);
        // created_at 由 @CreatedDate 設成「現在」，會被算成播種當天的新訂閱；回填到訂閱起日，與訂單一致
        jdbc.update("UPDATE member_subscription SET created_at = ?, updated_at = ? WHERE member_subscription_id = ?",
                toDbUtc(startedAt), toDbUtc(startedAt), saved.getMemberSubscriptionId());
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

        // 舊版種子的殘留特徵：團課全掛在同一位教練、種子私教課掛錯專長的教練、還留著舊的「體態雕塑」課名。
        // 只看「種子自己建的」那幾筆（依課名辨識），後台手動新增的課程不列入判斷——
        // 先前用 privateCourses.count() != 3 當條件，管理員多建一門課就整批重播，是資料流失的來源。
        long distinctTrainers = groupCourses.findAll().stream().map(gc -> gc.getTrainer().getId()).distinct().count();
        List<PrivateCourse> allPrivate = privateCourses.findAll();
        boolean hasInvalidPrivateTrainer = allPrivate.stream()
                .filter(pc -> SEED_PRIVATE_COURSE_NAMES.contains(pc.getName()))
                .anyMatch(pc -> pc.getTrainer() == null || pc.getTrainer().getSpecialty() == null
                        || !pc.getTrainer().getSpecialty().contains("重量訓練"));
        boolean hasBodySculptingCourse = allPrivate.stream().anyMatch(pc -> pc.getName().contains("體態雕塑"));
        boolean legacySeedDetected = (groupCourses.count() > 0 && distinctTrainers <= 1)
                || hasInvalidPrivateTrainer || hasBodySculptingCourse;
        if (legacySeedDetected && hasLiveTransactionalData()) {
            // 重播會把訂單、預約、已購堂數全部刪掉；資料庫裡已經有真實交易時寧可留著舊種子。
            // 而且 member_course_quota.order_item_id 參照 order_item，硬刪會撞 FK 讓後端起不來。
            log.warn("偵測到舊版種子課程，但資料庫已有真實訂單／預約／已購堂數，略過重播。"
                    + "要回到乾淨種子請重建資料庫（frontend: npm run test:e2e:fresh）");
        } else if (legacySeedDetected) {
            log.info("偵測到舊版種子課程（團課教練未分散／私教教練專長不符／含舊私教名稱），重新清除並播種以分配至 12 位教練…");
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
        ensureCoachSlots(seededTrainers);
    }

    /**
     * 資料庫裡是否已有「真實流程」產生的交易資料：私教預約、團課報名、買課得到的堂數、
     * 非樣本訂單。有的話代表這不是一顆只有種子的展示資料庫，任何整批刪除都不該執行。
     * 樣本訂單以 {@value #SAMPLE_ORDER_PREFIX}／{@value #SEED_SUBSCRIPTION_ORDER_NO}
     * 前綴辨識。
     */
    private boolean hasLiveTransactionalData() {
        Integer live = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM coach_booking)
                     + (SELECT COUNT(*) FROM group_class_booking)
                     + (SELECT COUNT(*) FROM member_course_quota WHERE order_item_id IS NOT NULL)
                     + (SELECT COUNT(*) FROM orders WHERE order_no IS NULL OR order_no NOT LIKE 'SEED-%')
                """, Integer.class);
        return live != null && live > 0;
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
                    toDbUtc(placedAt), toDbUtc(placedAt), savedOrder.getOrderId());
            jdbc.update("UPDATE order_item SET created_at = ? WHERE order_id = ?",
                    toDbUtc(placedAt), savedOrder.getOrderId());
            if (savedOrder.getPaidAt() != null) {
                jdbc.update("UPDATE orders SET paid_at = ? WHERE order_id = ?", toDbUtc(placedAt), savedOrder.getOrderId());
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
                new Seed("高彈力抗暴瑜珈球", "620", 25, "Nike", "藍", "核心穩定訓練與伸展。", "器材", "65cm", "藍", "PVC", "臺灣", 0.8),

                // 配件 (15 items)
                new Seed("牛皮健身腰帶", "1580", 20, "Valeo", "棕色", "硬舉深蹲核心支撐，厚度10mm。", "配件", "M", "棕", "真皮", "美國", 0.9),
                new Seed("專業重訓拉力帶", "450", 60, "Harbinger", "黑色", "護腕拉力帶一體，防滑耐磨。", "配件", "均碼", "黑", "尼龍", "美國", 0.15),
                new Seed("透氣運動防滑手套", "520", 50, "Nike", "灰黑", "透氣網布，掌心避震橡膠。", "配件", "M", "灰黑", "尼龍橡膠", "臺灣", 0.1),
                new Seed("加壓型針織護膝(一對)", "780", 40, "Bauerfeind", "藍灰", "3D針織加壓保護，減輕膝蓋負擔。", "配件", "M", "藍灰", "針織", "德國",
                        0.2),
                new Seed("專業運動防滑粉", "150", 150, "Rogue", "白", "強力吸汗防滑，舉重體操專用。", "配件", "150g", "白", "碳酸鎂粉", "美國", 0.15),
                new Seed("高階運動水壺 1000ml", "290", 80, "Nalgene", "透明灰", "Tritan安全材質，防漏耐摔。", "配件", "1000ml", "透明灰",
                        "Tritan", "美國", 0.2),
                new Seed("多功能乾濕分離運動包", "1280", 30, "UnderArmour", "深藍", "底部獨立鞋袋，乾濕分離設計。", "配件", "35L", "深藍", "尼龍", "臺灣",
                        0.8),
                new Seed("超細纖維吸汗運動毛巾", "180", 100, "Willy", "綠色", "吸水快乾，健身房擦汗必備。", "配件", "100x20cm", "綠", "超細纖維", "臺灣",
                        0.1),
                new Seed("運動排汗止汗頭帶", "150", 120, "Nike", "白色", "高彈力排汗材質，防止汗水入眼。", "配件", "均碼", "白", "彈性纖維", "臺灣", 0.05),
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
                if (p.getBrand() == null || p.getBrand().trim().isEmpty()) {
                    p.setBrand(seed.brand());
                    changed = true;
                }
                if (p.getFlavor() == null || p.getFlavor().trim().isEmpty()) {
                    p.setFlavor(seed.flavor());
                    changed = true;
                }
                if (p.getSize() == null || p.getSize().trim().isEmpty()) {
                    p.setSize(seed.size());
                    changed = true;
                }
                if (p.getColor() == null || p.getColor().trim().isEmpty()) {
                    p.setColor(seed.color());
                    changed = true;
                }
                if (p.getMaterial() == null || p.getMaterial().trim().isEmpty()) {
                    p.setMaterial(seed.material());
                    changed = true;
                }
                if (p.getOrigin() == null || p.getOrigin().trim().isEmpty()) {
                    p.setOrigin(seed.origin());
                    changed = true;
                } else if ("中國".equals(p.getOrigin())) {
                    p.setOrigin("臺灣");
                    changed = true;
                }
                if (p.getWeight() == null || p.getWeight() == 0) {
                    p.setWeight(seed.weight());
                    changed = true;
                }
                // 圖片只回填空值，後台自行換過的圖不動
                String image = SEED_PRODUCT_IMAGES.get(seed.name());
                if (image != null && isBlank(p.getImageUrl())) {
                    p.setImageUrl(image);
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
            product.setImageUrl(SEED_PRODUCT_IMAGES.get(seed.name()));
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
            // 已有團課就不重播，只把種子課程缺的封面回填（舊資料庫升級後才會有圖）
            int filled = 0;
            for (GroupCourse gc : groupCourses.findAll()) {
                String image = SEED_GROUP_COURSE_IMAGES.get(gc.getName());
                if (image != null && isBlank(gc.getImageUrl())) {
                    gc.setImageUrl(image);
                    groupCourses.save(gc);
                    filled++;
                }
            }
            if (filled > 0) {
                log.info("已回填 {} 筆種子團課的封面圖", filled);
            }
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
            gc.setImageUrl(SEED_GROUP_COURSE_IMAGES.get(seed.name()));
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
            // 已有私教課就不重播，只把種子課程缺的封面回填
            int filled = 0;
            for (PrivateCourse pc : privateCourses.findAll()) {
                String image = SEED_PRIVATE_COURSE_IMAGES.get(pc.getName());
                if (image != null && isBlank(pc.getImageUrl())) {
                    pc.setImageUrl(image);
                    privateCourses.save(pc);
                    filled++;
                }
            }
            if (filled > 0) {
                log.info("已回填 {} 筆種子私教課的封面圖", filled);
            }
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
                new Seed(SEED_PRIVATE_COURSE_NAMES.get(0), "首次體驗，單堂一對一指導，評估體能並初步規劃重訓方向。", 800, 1, false),
                new Seed(SEED_PRIVATE_COURSE_NAMES.get(1), "從姿勢與呼吸打底，建立深蹲、硬舉、臥推三大動作基礎。", 6500, 5, false),
                new Seed(SEED_PRIVATE_COURSE_NAMES.get(2), "依 InBody 數據客製課表，系統化進行進階重訓，十堂完成階段性目標。", 12000, 10, false));

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
            course.setImageUrl(SEED_PRIVATE_COURSE_IMAGES.get(seed.name()));
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
                new CouponSeed("商城商品八五折優惠券", "SUPP85", CouponType.PERCENT, new BigDecimal("15"), new BigDecimal("1000"),
                        CouponApplicableType.PRODUCT, "商城全系列商品適用，滿1000享85折優惠！"),
                new CouponSeed("商城商品滿額九折券", "DUMBBELL90", CouponType.PERCENT, new BigDecimal("10"),
                        new BigDecimal("800"), CouponApplicableType.PRODUCT, "商城全系列商品適用，滿800享9折優惠！"),
                new CouponSeed("商城商品八折特惠券", "ACC80", CouponType.PERCENT, new BigDecimal("20"), new BigDecimal("300"),
                        CouponApplicableType.PRODUCT, "商城全系列商品適用，滿300享8折優惠！"),
                new CouponSeed("商城商品折50優惠券", "WHEY50", CouponType.CASH, new BigDecimal("50"), new BigDecimal("500"),
                        CouponApplicableType.PRODUCT, "商城全系列商品適用，滿500折50！"),
                new CouponSeed("商城商品88折優惠券", "STRAP88", CouponType.PERCENT, new BigDecimal("12"), new BigDecimal("400"),
                        CouponApplicableType.PRODUCT, "商城全系列商品適用，滿400享88折！"),
                new CouponSeed("全館慶典狂歡券", "CELEBRATE200", CouponType.CASH, new BigDecimal("200"),
                        new BigDecimal("2000"), CouponApplicableType.ALL, "全館不限商品，滿2000折200！"),
                new CouponSeed("新會員首購好禮", "FIRSTFREE", CouponType.PERCENT, new BigDecimal("5"), new BigDecimal("100"),
                        CouponApplicableType.ALL, "新朋友體驗好禮，滿100享95折優惠！"),
                new CouponSeed("商城商品滿額折150券", "KETTLEBELL150", CouponType.CASH, new BigDecimal("150"),
                        new BigDecimal("1500"), CouponApplicableType.PRODUCT, "商城全系列商品適用，滿1500折150！"));

        int created = 0;
        for (CouponSeed seed : seeds) {
            java.util.Optional<Coupon> existing = couponRepository.findByCode(seed.code());
            if (existing.isPresent()) {
                Coupon coupon = existing.get();
                boolean updated = false;
                if (!seed.name().equals(coupon.getName())) {
                    coupon.setName(seed.name());
                    updated = true;
                }
                if (!seed.description().equals(coupon.getDescription())) {
                    coupon.setDescription(seed.description());
                    updated = true;
                }
                // 如果是 FIRSTFREE 且數量不是剩餘 1 張，我們自動修正它，確保錄影每次都對
                if ("FIRSTFREE".equals(seed.code())) {
                    if (coupon.getTotalQuantity() != 10 || coupon.getClaimedQuantity() != 9) {
                        coupon.setTotalQuantity(10);
                        coupon.setClaimedQuantity(9);
                        updated = true;
                        log.info("已修正 FIRSTFREE 優惠券為剩餘 1 張（總數 10，已領取 9）以供錄影");
                    }
                }
                if (updated) {
                    couponRepository.save(coupon);
                }
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

            // 如果是 FIRSTFREE，預設設為 10 張，且已被領取 9 張（剩餘 1 張）
            if ("FIRSTFREE".equals(seed.code())) {
                coupon.setTotalQuantity(10);
                coupon.setClaimedQuantity(9);
            } else {
                coupon.setTotalQuantity(1000); // 限量 1000 張
                coupon.setClaimedQuantity(0);
            }

            coupon.setStartDate(LocalDate.now());
            coupon.setEndDate(LocalDate.now().plusYears(1));
            couponRepository.save(coupon);
            created++;
        }
        if (created > 0) {
            log.info("已建立 {} 筆測試優惠券（總共 17 項，分屬產品、課程、會籍、全館）", created);
        }
    }

    private void ensureCoachSlots(List<Trainer> seededTrainers) {
        if (seededTrainers.isEmpty()) {
            return;
        }
        if (coachSlots.count() > 0) {
            return;
        }

        log.info("開始播種 6 月至 10 月的教練私教預約時段...");
        int count = 0;
        java.time.LocalDate startDate = java.time.LocalDate.of(2026, 6, 1);
        java.time.LocalDate endDate = java.time.LocalDate.of(2026, 10, 31);

        java.time.LocalDate currentWeekStart = startDate;
        while (!currentWeekStart.isAfter(endDate)) {
            for (Trainer trainer : seededTrainers) {
                int firstDayOffset = Math.abs((trainer.getId() * 7 + currentWeekStart.hashCode())) % 5;
                int secondDayOffset = (firstDayOffset + 2) % 5;

                int firstHour = 9 + Math.abs((trainer.getId() * 3 + currentWeekStart.hashCode())) % 8;
                int secondHour = 9 + Math.abs((trainer.getId() * 5 + currentWeekStart.hashCode())) % 8;

                java.time.LocalDate day1 = currentWeekStart.plusDays(firstDayOffset);
                if (!day1.isBefore(startDate) && !day1.isAfter(endDate)) {
                    CoachSlot slot1 = new CoachSlot();
                    slot1.setTrainer(trainer);
                    slot1.setStartAt(LocalDateTime.of(day1, java.time.LocalTime.of(firstHour, 0)));
                    slot1.setEndAt(LocalDateTime.of(day1, java.time.LocalTime.of(firstHour + 1, 0)));
                    slot1.setStatus(SlotStatus.AVAILABLE);
                    coachSlots.save(slot1);
                    count++;
                }

                java.time.LocalDate day2 = currentWeekStart.plusDays(secondDayOffset);
                if (!day2.isBefore(startDate) && !day2.isAfter(endDate)) {
                    CoachSlot slot2 = new CoachSlot();
                    slot2.setTrainer(trainer);
                    slot2.setStartAt(LocalDateTime.of(day2, java.time.LocalTime.of(secondHour, 0)));
                    slot2.setEndAt(LocalDateTime.of(day2, java.time.LocalTime.of(secondHour + 1, 0)));
                    slot2.setStatus(SlotStatus.AVAILABLE);
                    coachSlots.save(slot2);
                    count++;
                }
            }
            currentWeekStart = currentWeekStart.plusWeeks(1);
        }
        log.info("已成功播種 {} 筆教練預約時段 (6月-10月)！", count);
    }

    // ─────────────── 統計展示資料：讓營運統計／BI 圖表有真實分布 ───────────────

    /** 擬真示範會員的 email 格式；統計展示資料以此辨識，不會動到手動註冊的會員 */
    private static final String DEMO_MEMBER_EMAIL_FMT = "seed-member%02d@local.test";
    private static final int DEMO_MEMBER_COUNT = 36;
    /**
     * 樣本訂單總數；前 {@value #SAMPLE_ORDER_COUNT} 筆是 ensureSampleOrders 的，其餘由
     * ensureMoreSampleOrders 補齊
     */
    private static final int DEMO_ORDER_TARGET = 60;
    /** 付款方式輪替：信用卡佔多數，其餘平均分佈——代碼須存在於前端 paymentLabels.js 的對照表 */
    private static final List<String> DEMO_PAYMENT_METHODS = List.of(
            "Credit_CreditCard", "Credit_CreditCard", "ATM", "Credit_CreditCard",
            "CVS", "WebATM", "Credit_CreditCard", "BARCODE");
    /** 私教課樣本訂單編號前綴；與 SEED-ORD-（商品）分開計數，各自冪等 */
    private static final String SAMPLE_COURSE_ORDER_PREFIX = "SEED-CRS-";
    /** 私教課樣本訂單筆數：每門種子私教課各一筆已付款訂單 */
    private static final int SAMPLE_COURSE_ORDER_COUNT = 3;

    /**
     * 擬真示範會員：中文姓名、性別、生日、城市齊全，created_at 攤在最近 90 天
     * （二次曲線：早期疏、近期密），讓「每日新會員」趨勢有起伏而不是全部擠在重建日。
     * 冪等：逐 email 檢查，已存在就只收集回傳不改動。
     */
    private List<Member> ensureRealisticMembers() {
        String[] surnames = {
                "陳", "林", "黃", "張", "李", "王", "吳", "劉", "蔡", "楊", "許", "鄭",
                "謝", "郭", "洪", "曾", "邱", "廖", "賴", "周", "葉", "蘇", "莊", "呂",
                "江", "何", "蕭", "羅", "高", "潘", "簡", "朱", "鍾", "彭", "游", "詹" };
        // 偶數位女性、奇數位男性，與下方 gender 判斷對齊
        String[] givenNames = {
                "淑芬", "家豪", "雅婷", "志明", "佳穎", "俊傑", "怡君", "建宏", "美玲", "冠廷", "雅雯", "承翰",
                "詩涵", "宗翰", "佩珊", "柏翰", "宜蓁", "冠宇", "欣妤", "家瑋", "心怡", "哲瑋", "郁婷", "明哲",
                "曉慧", "育誠", "麗華", "世豪", "芷若", "彥廷", "依婷", "家銘", "韻如", "志偉", "惠雯", "俊宏" };
        record City(String city, String district, String zip) {
        }
        List<City> cities = List.of(
                new City("台北市", "中山區", "104"), new City("台北市", "大安區", "106"),
                new City("台北市", "信義區", "110"), new City("新北市", "板橋區", "220"),
                new City("新北市", "中和區", "235"), new City("新北市", "新莊區", "242"),
                new City("桃園市", "桃園區", "330"), new City("桃園市", "中壢區", "320"),
                new City("台中市", "西屯區", "407"), new City("台中市", "北屯區", "406"),
                new City("台南市", "東區", "701"), new City("高雄市", "左營區", "813"),
                new City("高雄市", "三民區", "807"), new City("新竹市", "東區", "300"));

        List<Member> result = new java.util.ArrayList<>();
        int created = 0;
        for (int i = 0; i < DEMO_MEMBER_COUNT; i++) {
            String email = String.format(DEMO_MEMBER_EMAIL_FMT, i + 1);
            Account existing = accounts.findByEmailIgnoreCase(email).orElse(null);
            if (existing != null) {
                members.findByAccountId(existing.getId()).ifPresent(result::add);
                continue;
            }
            Account acc = new Account();
            acc.setEmail(email);
            acc.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
            acc.getRoles().add(AccountType.MEMBER);
            acc = accounts.save(acc);

            City city = cities.get(i % cities.size());
            Member m = new Member();
            m.setAccount(acc);
            m.setLastName(surnames[i]);
            m.setFirstName(givenNames[i]);
            m.setPhone("09" + String.format("%08d", 61000000 + i));
            m.setStatus(UserStatus.ACTIVE);
            m.setGender(i % 2 == 0 ? Gender.F : Gender.M);
            m.setDob(LocalDate.of(1972 + (i * 31) % 34, (i % 12) + 1, (i * 7) % 27 + 1));
            m.setCity(city.city());
            m.setDistrict(city.district());
            m.setZipCode(city.zip());
            m = members.save(m);

            // created_at 由 @PrePersist 設成「現在」，比照 ensureSampleOrders 直接下 SQL 回填分散日期
            // 最晚的一位至少是昨天：公式在 i = N-1 時會算出 0 天前，加上 withHour 還可能是未來時刻，
            // 重建資料庫那天營運統計的「新會員」就會憑空多 1 位（2026-09-07 demo 彩排實際發生）
            int daysAgo = Math.max(1, 89 - (i * i * 89) / ((DEMO_MEMBER_COUNT - 1) * (DEMO_MEMBER_COUNT - 1)));
            LocalDateTime createdAt = LocalDateTime.now().minusDays(daysAgo)
                    .withHour(9 + (i * 5) % 12).withMinute((i * 17) % 60).withSecond(0).withNano(0);
            jdbc.update("UPDATE member SET created_at = ?, updated_at = ? WHERE member_id = ?",
                    toDbUtc(createdAt), toDbUtc(createdAt), m.getId());
            result.add(m);
            created++;
        }
        if (created > 0) {
            log.info("已建立 {} 位擬真示範會員（seed-memberNN@local.test，註冊日攤在最近 90 天）", created);
        }
        return result;
    }

    /**
     * 把樣本訂單從 {@value #SAMPLE_ORDER_COUNT} 筆補到 {@value #DEMO_ORDER_TARGET} 筆：
     * 買家輪流用示範會員、付款方式輪替、日期攤在最近 90 天且時辰含台北清晨
     * （清晨單正是 UTC 日期桶 bug 的驗證樣本）。「模擬付款」來自 E2E 的 simulate-payment，
     * 不造假刪除，靠這批真實付款方式的訂單把它稀釋成小切片。
     * 冪等：以 SEED-ORD- 前綴計數，補滿即停。
     */
    private void ensureMoreSampleOrders(List<Member> demoMembers) {
        if (demoMembers.isEmpty()) {
            return;
        }
        long existing = orders.findAll().stream()
                .filter(o -> o.getOrderNo() != null && o.getOrderNo().startsWith(SAMPLE_ORDER_PREFIX))
                .count();
        if (existing >= DEMO_ORDER_TARGET) {
            return;
        }
        List<Product> sellable = products.findAll().stream()
                .filter(p -> p.getPrice() != null)
                .toList();
        if (sellable.isEmpty()) {
            return;
        }

        int created = 0;
        for (int i = (int) existing; i < DEMO_ORDER_TARGET; i++) {
            // 全部由固定公式導出，播種結果可重現
            int daysAgo = (i * 83) % 90;
            int hour = 6 + (i * 11) % 16; // 6–21 時，含台北清晨（跨 UTC 日界的樣本）
            OrderStatus status = switch (i % 12) {
                case 4 -> OrderStatus.CANCELLED;
                case 7 -> OrderStatus.REFUNDED;
                default -> OrderStatus.PAID;
            };
            Product product = sellable.get((i * 13) % sellable.size());
            int quantity = 1 + (i % 3);
            Member buyer = demoMembers.get((i * 7) % demoMembers.size());
            String method = DEMO_PAYMENT_METHODS.get(i % DEMO_PAYMENT_METHODS.size());

            BigDecimal unit = product.getPrice();
            BigDecimal amount = unit.multiply(BigDecimal.valueOf(quantity));
            LocalDateTime placedAt = LocalDateTime.now().minusDays(daysAgo)
                    .withHour(hour).withMinute((i * 23) % 60).withSecond(0).withNano(0);
            String no = SAMPLE_ORDER_PREFIX + String.format("%03d", i + 1);

            tw.com.ispan.gym.entity.order.Order order = new tw.com.ispan.gym.entity.order.Order(
                    buyer, no, no, amount, BigDecimal.ZERO, amount, amount, null,
                    buyer.getLastName() + buyer.getFirstName(), buyer.getPhone(), null, null);
            switch (status) {
                case PAID -> order.markPaid(method, no);
                case REFUNDED -> {
                    order.markPaid(method, no);
                    order.markRefunded();
                }
                case CANCELLED -> order.cancel("測試資料：模擬取消訂單");
                default -> {
                }
            }
            tw.com.ispan.gym.entity.order.Order savedOrder = orders.save(order);
            orderItems.save(new OrderItem(savedOrder, product, product.getName(), unit, quantity));

            if (status == OrderStatus.PAID || status == OrderStatus.REFUNDED) {
                PaymentTransaction payment = new PaymentTransaction(savedOrder, "ECPAY", no, amount, "TWD");
                payment.setTradeNo(no);
                payment.setPaymentMethod(method);
                payment.setStatus("SUCCESS");
                payment.setPaidAt(placedAt);
                payment.setRtnCode(1);
                payment.setRtnMsg("交易成功");
                paymentTransactions.save(payment);
            }

            jdbc.update("UPDATE orders SET created_at = ?, updated_at = ? WHERE order_id = ?",
                    toDbUtc(placedAt), toDbUtc(placedAt), savedOrder.getOrderId());
            jdbc.update("UPDATE order_item SET created_at = ? WHERE order_id = ?",
                    toDbUtc(placedAt), savedOrder.getOrderId());
            if (savedOrder.getPaidAt() != null) {
                jdbc.update("UPDATE orders SET paid_at = ? WHERE order_id = ?", toDbUtc(placedAt), savedOrder.getOrderId());
            }
            created++;
        }
        if (created > 0) {
            log.info("已補 {} 筆樣本訂單（買家/付款方式/日期分散，總數 {}）", created, DEMO_ORDER_TARGET);
        }
    }

    /**
     * 私教課樣本訂單。上面兩批樣本訂單的明細全在 order_item.product_id 那一側，資料庫裡從沒有人
     * 買過私教課：營運統計「品項型別＝課程」的排行永遠是空的，營收組成也只有商品與會籍兩片。
     * 這裡對每門種子私教課各建一筆已付款訂單，日期攤在最近三週（5／12／19 天前，都不是今天，
     * 不影響「今天」的 KPI），買家輪流用示範會員，付款方式輪替。
     * 只建訂單、明細與付款紀錄，不發堂數：member_course_quota 是 hasRealTransactionData 判斷
     * 「真實交易」的依據之一，發了會讓種子誤判這不是展示資料庫；報表只看 order_item。
     * 冪等：以 SEED-CRS- 前綴計數，補滿即停。
     */
    private void ensureSampleCourseOrders(List<Member> demoMembers) {
        if (demoMembers.isEmpty()) {
            return;
        }
        long existing = orders.findAll().stream()
                .filter(o -> o.getOrderNo() != null && o.getOrderNo().startsWith(SAMPLE_COURSE_ORDER_PREFIX))
                .count();
        if (existing >= SAMPLE_COURSE_ORDER_COUNT) {
            return;
        }
        // 只挑種子私教課（以名稱辨識），管理員自己建的課不替它造訂單；種子課不在就退回任何上架課
        List<PrivateCourse> active = privateCourses.findByStatus(CourseStatus.ACTIVE).stream()
                .filter(c -> c.getPrice() != null)
                .sorted(java.util.Comparator.comparing(PrivateCourse::getPrivateCourseId))
                .toList();
        List<PrivateCourse> seeded = active.stream()
                .filter(c -> SEED_PRIVATE_COURSE_NAMES.contains(c.getName()))
                .toList();
        List<PrivateCourse> pool = seeded.isEmpty() ? active : seeded;
        if (pool.isEmpty()) {
            return;
        }

        int created = 0;
        for (int i = (int) existing; i < SAMPLE_COURSE_ORDER_COUNT; i++) {
            PrivateCourse course = pool.get(i % pool.size());
            int daysAgo = 5 + i * 7;
            int hour = 10 + (i * 5) % 9;
            Member buyer = demoMembers.get((i * 11) % demoMembers.size());
            String method = DEMO_PAYMENT_METHODS.get((i * 3) % DEMO_PAYMENT_METHODS.size());
            BigDecimal unit = course.getPrice();
            BigDecimal amount = unit;
            LocalDateTime placedAt = LocalDateTime.now().minusDays(daysAgo)
                    .withHour(hour).withMinute((i * 17) % 60).withSecond(0).withNano(0);
            String no = SAMPLE_COURSE_ORDER_PREFIX + String.format("%03d", i + 1);

            tw.com.ispan.gym.entity.order.Order order = new tw.com.ispan.gym.entity.order.Order(
                    buyer, no, no, amount, BigDecimal.ZERO, amount, amount, null,
                    buyer.getLastName() + buyer.getFirstName(), buyer.getPhone(), null, null);
            order.markPaid(method, no);
            tw.com.ispan.gym.entity.order.Order savedOrder = orders.save(order);
            orderItems.save(new OrderItem(savedOrder, course, course.getName(), unit, 1));

            PaymentTransaction payment = new PaymentTransaction(savedOrder, "ECPAY", no, amount, "TWD");
            payment.setTradeNo(no);
            payment.setPaymentMethod(method);
            payment.setStatus("SUCCESS");
            payment.setPaidAt(placedAt);
            payment.setRtnCode(1);
            payment.setRtnMsg("交易成功");
            paymentTransactions.save(payment);

            jdbc.update("UPDATE orders SET created_at = ?, updated_at = ?, paid_at = ? WHERE order_id = ?",
                    toDbUtc(placedAt), toDbUtc(placedAt), toDbUtc(placedAt), savedOrder.getOrderId());
            jdbc.update("UPDATE order_item SET created_at = ? WHERE order_id = ?",
                    toDbUtc(placedAt), savedOrder.getOrderId());
            created++;
        }
        if (created > 0) {
            log.info("已建立 {} 筆私教課樣本訂單（營運統計的課程排行與營收組成才有資料）", created);
        }
    }

    /**
     * 過去 4 週的團課場次＋報名：現行種子只建未來場次（給預約流程用），
     * 報表的「團課滿座率」看的是過去，一直是空的。只為種子課程（以封面圖對照表辨識）補場次，
     * 報名列與 bookedCount 完全一致，不留「有數字沒名單」的怪資料。
     * 冪等：已存在任何過去場次就不再補。
     */
    private void ensurePastGroupClassSessions(List<Member> demoMembers) {
        if (demoMembers.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean hasPastSession = groupClassSessions.findAll().stream()
                .anyMatch(s -> s.getStartAt().isBefore(now.minusDays(1)));
        if (hasPastSession) {
            return;
        }
        List<GroupCourse> seedCourses = groupCourses.findAll().stream()
                .filter(gc -> SEED_GROUP_COURSE_IMAGES.containsKey(gc.getName()))
                .toList();
        if (seedCourses.isEmpty()) {
            return;
        }
        // 教室沿用該課程未來場次的教室；沒有就退回重訓教室
        java.util.Map<String, ClassRoom> roomByCourse = new java.util.HashMap<>();
        for (GroupClassSession s : groupClassSessions.findAll()) {
            roomByCourse.putIfAbsent(s.getCourse().getName(), s.getRoomName());
        }

        int memberCursor = 0;
        int sessionCount = 0;
        int bookingCount = 0;
        for (int c = 0; c < seedCourses.size(); c++) {
            GroupCourse course = seedCourses.get(c);
            for (int w = 0; w < 3; w++) {
                int daysAgo = 26 - w * 7 - (c % 4);
                LocalDateTime start = now.minusDays(daysAgo)
                        .withHour(8 + (c * 5 + w * 3) % 13).withMinute(0).withSecond(0).withNano(0);
                int capacity = course.getMaxCapacity();
                // 滿座率 50%–95%，各場次不同
                int booked = Math.min(capacity, Math.max(3, capacity * (50 + (c * 7 + w * 13) % 45) / 100));

                GroupClassSession session = new GroupClassSession();
                session.setCourse(course);
                session.setRoomName(roomByCourse.getOrDefault(course.getName(), ClassRoom.WEIGHT_ROOM));
                session.setStartAt(start);
                session.setEndAt(start.plusHours(1));
                session.setCapacity(capacity);
                session.setStatus(GroupClassSessionStatus.SCHEDULED);
                session.setBookedCount(booked);
                GroupClassSession saved = groupClassSessions.save(session);
                sessionCount++;

                // 連續取用示範會員，單一場次內必不重複（booked <= 25 < 36），
                // 滿足 (session_id, member_id) WHERE BOOKED 的部分唯一索引
                for (int b = 0; b < booked; b++) {
                    GroupClassBooking gb = new GroupClassBooking();
                    gb.setSession(saved);
                    gb.setMember(demoMembers.get(memberCursor++ % demoMembers.size()));
                    gb.setStatus(GroupClassBookingStatus.BOOKED);
                    groupClassBookings.save(gb);
                    bookingCount++;
                }
                // 報名時間回填到開課前兩天，前端明細的時間軸才合理
                jdbc.update("UPDATE group_class_booking SET created_at = ?, updated_at = ? WHERE session_id = ?",
                        toDbUtc(start.minusDays(2)), toDbUtc(start.minusDays(2)), saved.getId());
            }
        }
        log.info("已補 {} 筆過去團課場次與 {} 筆報名（滿座率 50–95%）", sessionCount, bookingCount);
    }

    /**
     * 過去 8 週的私教時段預約：既有種子把 6–10 月的時段全建成 AVAILABLE，
     * 報表的「教練時段使用率」全是 0。挑過去時段的六成建立已完成的預約
     * （quota → booking → slot 轉 BOOKED 一路做全，不留「BOOKED 卻沒有預約列」的怪資料）。
     * 冪等：以 check_in_key 的 SEED- 前綴辨識，已播過就跳過。
     */
    private void ensurePastCoachSlotBookings(List<Member> demoMembers) {
        if (demoMembers.isEmpty()) {
            return;
        }
        Integer seeded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM coach_booking WHERE check_in_key LIKE 'SEED-%'", Integer.class);
        if (seeded != null && seeded > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<CoachSlot> pastAvailable = coachSlots.findAll().stream()
                .filter(s -> s.getStatus() == SlotStatus.AVAILABLE
                        && s.getStartAt().isBefore(now)
                        && s.getStartAt().isAfter(now.minusDays(56)))
                .sorted(java.util.Comparator.comparing(CoachSlot::getStartAt))
                .toList();

        java.util.Map<String, MemberCourseQuota> quotaCache = new java.util.HashMap<>();
        int cursor = 0;
        int made = 0;
        for (int j = 0; j < pastAvailable.size(); j++) {
            CoachSlot slot = pastAvailable.get(j);
            if ((j * 7 + slot.getTrainer().getId() * 3) % 10 >= 6) {
                continue; // 六成預約率，各教練錯落
            }
            Member member = demoMembers.get(cursor++ % demoMembers.size());
            String key = member.getId() + "-" + slot.getTrainer().getId();
            MemberCourseQuota quota = quotaCache.computeIfAbsent(key, k -> {
                MemberCourseQuota q = new MemberCourseQuota();
                q.setMember(member);
                q.setTrainer(slot.getTrainer());
                q.setCourseName("一對一重量訓練額度");
                q.setRemainingCourse(2);
                q.setReservedCourse(0);
                q.setUsedCourse(0);
                q.setExpiryDate(now.plusMonths(6));
                return memberCourseQuotas.save(q);
            });
            quota.setUsedCourse(quota.getUsedCourse() + 1);
            memberCourseQuotas.save(quota);

            slot.setStatus(SlotStatus.BOOKED);
            coachSlots.save(slot);

            CoachBooking booking = new CoachBooking();
            booking.setCoachSlot(slot);
            booking.setMember(member);
            booking.setQuota(quota);
            booking.setStatus(CoachBookingStatus.COMPLETED);
            booking.setCheckInKey("SEED-" + slot.getId());
            booking.setCheckInTime(slot.getEndAt());
            booking.setCheckInApprover(slot.getTrainer());
            coachBookings.save(booking);
            // 預約成立時間回填到上課前三天
            jdbc.update("UPDATE coach_booking SET created_at = ?, updated_at = ? WHERE check_in_key = ?",
                    toDbUtc(slot.getStartAt().minusDays(3)), toDbUtc(slot.getStartAt().minusDays(3)), "SEED-" + slot.getId());
            made++;
        }
        if (made > 0) {
            log.info("已補 {} 筆過去私教預約（過去 8 週、約六成使用率，時段轉 BOOKED）", made);
        }
    }
}
