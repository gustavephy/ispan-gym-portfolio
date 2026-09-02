package tw.com.ispan.gym.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import tw.com.ispan.gym.dto.order.CancelOrderRequest;
import tw.com.ispan.gym.dto.order.CreateOrderRequest;
import tw.com.ispan.gym.dto.order.DirectCheckoutRequest;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderPreviewResponse;
import tw.com.ispan.gym.dto.order.SubscriptionCheckoutRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.cart.CartItem;
import tw.com.ispan.gym.entity.coach.MemberCourseQuota;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.entity.order.OrderStatusHistory;
import tw.com.ispan.gym.entity.payment.PaymentRefund;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.coupon.CouponType;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;
import tw.com.ispan.gym.enums.order.InvoiceType;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.enums.product.ProductStatus;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;
import tw.com.ispan.gym.event.order.RefundCompletedEvent;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.DirectCheckoutNotAllowedException;
import tw.com.ispan.gym.exception.InsufficientStockException;
import tw.com.ispan.gym.exception.InvalidQuantityException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.exception.product.ProductUnavailableException;
import tw.com.ispan.gym.repository.cart.CartItemRepository;
import tw.com.ispan.gym.repository.coach.MemberCourseQuotaRepository;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;
import tw.com.ispan.gym.repository.member.MemberCouponRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.member.MemberSubscriptionRepository;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderStatusHistoryRepository;
import tw.com.ispan.gym.repository.payment.PaymentRefundRepository;
import tw.com.ispan.gym.repository.payment.PaymentTransactionRepository;
import tw.com.ispan.gym.repository.product.ProductRepository;
import tw.com.ispan.gym.repository.subscription.SubscriptionPlanRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 涵蓋金額計算（小計／折扣／百分比進位／折扣不得超過小計）、優惠券核銷的五道規則、
 * 庫存的扣除與還原、取消訂單依付款狀態的分流（未付款直接取消 vs 已付款轉待退款）、
 * 退款完成的還原與事件發佈，以及付款後履約（課程堂數、訂閱啟用）。
 *
 * 訂單/明細的 id 由資料庫產生、實體沒有 setter，測試用 ReflectionTestUtils 補上——
 * toDetailResponse() 會以 OrderItem::getId 排序，null id 會 NPE。
 *
 * 死結預防（依 productId 排序後才逐一取鎖）只驗證「有沒有依序取鎖」，
 * 沒有內嵌資料庫可以驗證真實的鎖順序，真正的死結行為要靠實際資料庫驗證。
 */
class OrderServiceTest {

    private static final Integer MEMBER_ID = 1;
    private static final Integer PRODUCT_ID = 100;
    private static final Integer OTHER_PRODUCT_ID = 101;
    private static final Integer COURSE_ID = 200;
    private static final Integer PLAN_ID = 300;
    private static final Integer COUPON_ID = 50;
    private static final Integer ORDER_ID = 900;
    private static final String COUPON_CODE = "SAVE100";
    /** 購物車結帳需要收件地址（實體商品一定要出貨），成功路徑一律帶上這個 */
    private static final String RECEIVER_ADDR = "台北市中正區測試路 1 號";
    private static final CreateOrderRequest ADDR_ONLY = new CreateOrderRequest(null, null, null, RECEIVER_ADDR);

    private final CartItemRepository cartItems = mock(CartItemRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final PrivateCourseRepository courses = mock(PrivateCourseRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final MemberCouponRepository memberCoupons = mock(MemberCouponRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository orderItems = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistories = mock(OrderStatusHistoryRepository.class);
    private final SubscriptionPlanRepository subscriptionPlans = mock(SubscriptionPlanRepository.class);
    private final MemberSubscriptionRepository subscriptions = mock(MemberSubscriptionRepository.class);
    private final MemberCourseQuotaRepository courseQuotas = mock(MemberCourseQuotaRepository.class);
    private final PaymentTransactionRepository paymentTransactions = mock(PaymentTransactionRepository.class);
    private final PaymentRefundRepository paymentRefunds = mock(PaymentRefundRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final OrderService service = new OrderService(cartItems, products, courses, members, coupons,
            memberCoupons, orders, orderItems, orderStatusHistories, subscriptionPlans, subscriptions,
            courseQuotas, paymentTransactions, paymentRefunds, events);

    // === 共用測試資料 ========================================================

    private Member member() {
        Account account = new Account();
        account.setEmail("buyer@example.com");
        Member member = new Member();
        member.setId(MEMBER_ID);
        member.setFirstName("小明");
        member.setLastName("王");
        member.setPhone("0912345678");
        member.setAccount(account);
        return member;
    }

    private Product product(Integer id, int stock, String price) {
        Product product = new Product();
        product.setProductId(id);
        product.setName("商品" + id);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setStatus(ProductStatus.ACTIVE);
        return product;
    }

    private PrivateCourse course(String price, int sessionPerUnit) {
        PrivateCourse course = new PrivateCourse();
        course.setPrivateCourseId(COURSE_ID);
        course.setName("一對一重訓");
        course.setPrice(new BigDecimal(price));
        course.setSessionPerUnit(sessionPerUnit);
        course.setStatus(CourseStatus.ACTIVE);
        course.setTrainer(new Trainer());
        return course;
    }

    private SubscriptionPlan plan(String price, int durationDays) {
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setSubscriptionPlanId(PLAN_ID);
        plan.setName("月費方案");
        plan.setPrice(new BigDecimal(price));
        plan.setDurationDays(durationDays);
        plan.setStatus("ACTIVE");
        return plan;
    }

    /** 預設是一張立即可用的現金券：ACTIVE、期間涵蓋今天、無低消、全品項適用 */
    private Coupon coupon(CouponType type, String value) {
        Coupon coupon = new Coupon();
        coupon.setCouponId(COUPON_ID);
        coupon.setCode(COUPON_CODE);
        coupon.setType(type);
        coupon.setValue(new BigDecimal(value));
        coupon.setMinSpend(BigDecimal.ZERO);
        coupon.setStatus(CouponStatus.ACTIVE);
        coupon.setApplicableType(CouponApplicableType.ALL);
        coupon.setStartDate(LocalDate.now().minusDays(1));
        coupon.setEndDate(LocalDate.now().plusDays(7));
        return coupon;
    }

    private MemberCoupon heldCoupon(Coupon coupon) {
        MemberCoupon mc = new MemberCoupon();
        mc.setMember(member());
        mc.setCoupon(coupon);
        mc.setStatus(MemberCouponStatus.UNUSED);
        return mc;
    }

    private void stubHoldsCoupon(Coupon coupon, MemberCoupon held) {
        when(coupons.findByCode(COUPON_CODE)).thenReturn(Optional.of(coupon));
        when(memberCoupons.findByMemberIdAndCouponCouponIdAndStatus(
                MEMBER_ID, COUPON_ID, MemberCouponStatus.UNUSED)).thenReturn(Optional.of(held));
    }

    private OrderItem productItem(Order order, Product product, int quantity, Integer id) {
        OrderItem item = new OrderItem(order, product, product.getName(), product.getPrice(), quantity);
        ReflectionTestUtils.setField(item, "orderItemId", id);
        return item;
    }

    private OrderItem courseItem(Order order, PrivateCourse course, int quantity, Integer id) {
        OrderItem item = new OrderItem(order, course, course.getName(), course.getPrice(), quantity);
        ReflectionTestUtils.setField(item, "orderItemId", id);
        return item;
    }

    private Order savedOrder() {
        Order order = new Order(member(), "GYM1", "ORD1", new BigDecimal("1000"), BigDecimal.ZERO,
                new BigDecimal("1000"), new BigDecimal("1000"), null, "王小明", "0912345678", null,
                "buyer@example.com");
        ReflectionTestUtils.setField(order, "orderId", ORDER_ID);
        return order;
    }

    private PaymentTransaction payment(Order order) {
        return new PaymentTransaction(order, "ECPAY", order.getMerchantTradeNo(),
                order.getTotalAmount(), "TWD");
    }

    /** orders.save() 回傳同一物件並補上 id，讓後續 toDetailResponse 查得到明細 */
    private void stubOrderSaveAssignsId() {
        when(orders.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            ReflectionTestUtils.setField(order, "orderId", ORDER_ID);
            return order;
        });
    }

    private void stubProductLock(Product... items) {
        for (Product product : items) {
            when(products.findByIdForUpdate(product.getProductId())).thenReturn(Optional.of(product));
        }
    }

    private void stubNoOrderItems() {
        when(orderItems.findAllByOrderIdOrderByIdAsc(any())).thenReturn(List.of());
    }

    private void stubCart(CartItem... items) {
        when(cartItems.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of(items));
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
    }

    // === createOrderFromCart()：金額與庫存 =====================================

    @Test
    void 購物車結帳加總各項小計並扣除庫存() {
        Product a = product(PRODUCT_ID, 10, "1000");
        Product b = product(OTHER_PRODUCT_ID, 5, "250.50");
        stubCart(new CartItem(member(), a, 2), new CartItem(member(), b, 3));
        stubProductLock(a, b);
        stubOrderSaveAssignsId();
        stubNoOrderItems();

        OrderDetailResponse response = service.createOrderFromCart(MEMBER_ID, ADDR_ONLY);

        // 2×1000 + 3×250.50 = 2751.50
        assertThat(response.totalAmount()).isEqualByComparingTo("2751.50");
        assertThat(response.status()).isEqualTo(OrderStatus.UNPAID);
        assertThat(a.getStock()).isEqualTo(8);
        assertThat(b.getStock()).isEqualTo(2);
        // 結帳後清空購物車
        verify(cartItems).deleteAllByMemberId(MEMBER_ID);
    }

    @Test
    void 空購物車不能結帳() {
        when(cartItems.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID, ADDR_ONLY))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("購物車是空的");

        verify(orders, never()).save(any());
    }

    @Test
    void 購物車結帳未填收件地址時擋下() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID,
                new CreateOrderRequest(null, "王小明", "0912345678", "  ")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("收件地址");

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("收件地址");

        verify(orders, never()).save(any());
    }

    @Test
    void 結帳時逐一對商品取鎖以避免死結() {
        Product a = product(PRODUCT_ID, 10, "1000");
        Product b = product(OTHER_PRODUCT_ID, 10, "500");
        stubCart(new CartItem(member(), a, 1), new CartItem(member(), b, 1));
        stubProductLock(a, b);
        stubOrderSaveAssignsId();
        stubNoOrderItems();

        service.createOrderFromCart(MEMBER_ID, ADDR_ONLY);

        // 併發防護：每一項商品都重新以鎖定查詢載入，而不是沿用購物車帶來的快照
        verify(products).findByIdForUpdate(PRODUCT_ID);
        verify(products).findByIdForUpdate(OTHER_PRODUCT_ID);
    }

    @Test
    void 結帳時庫存不足擋下且不建立訂單() {
        Product a = product(PRODUCT_ID, 1, "1000");
        stubCart(new CartItem(member(), a, 2));
        stubProductLock(a);

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID, ADDR_ONLY))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(a.getStock()).isEqualTo(1); // 沒有被扣
        verify(orders, never()).save(any());
        verify(cartItems, never()).deleteAllByMemberId(any());
    }

    @Test
    void 結帳時商品已下架擋下() {
        Product a = product(PRODUCT_ID, 99, "1000");
        a.setStatus(ProductStatus.INACTIVE);
        stubCart(new CartItem(member(), a, 1));
        stubProductLock(a);

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID, ADDR_ONLY))
                .isInstanceOf(ProductUnavailableException.class);

        verify(orders, never()).save(any());
    }

    @Test
    void 結帳時商品在鎖定查詢時已不存在回404() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));
        when(products.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID, ADDR_ONLY))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === 優惠券折抵計算 ========================================================

    @Test
    void 現金券折抵固定金額() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));
        stubProductLock(a);
        stubOrderSaveAssignsId();
        stubNoOrderItems();
        Coupon coupon = coupon(CouponType.CASH, "100");
        MemberCoupon held = heldCoupon(coupon);
        stubHoldsCoupon(coupon, held);

        OrderDetailResponse response = service.createOrderFromCart(MEMBER_ID,
                new CreateOrderRequest(COUPON_CODE, null, null, RECEIVER_ADDR));

        assertThat(response.totalAmount()).isEqualByComparingTo("900");
        // 券被核銷
        assertThat(held.getStatus()).isEqualTo(MemberCouponStatus.USED);
        assertThat(held.getUsedAt()).isNotNull();
    }

    @Test
    void 百分比券以四捨五入計算折抵金額() {
        Product a = product(PRODUCT_ID, 10, "333.33");
        stubCart(new CartItem(member(), a, 1));
        stubProductLock(a);
        stubOrderSaveAssignsId();
        stubNoOrderItems();
        Coupon coupon = coupon(CouponType.PERCENT, "15"); // 333.33 × 15% = 49.9995 → 50.00
        stubHoldsCoupon(coupon, heldCoupon(coupon));

        OrderDetailResponse response = service.createOrderFromCart(MEMBER_ID,
                new CreateOrderRequest(COUPON_CODE, null, null, RECEIVER_ADDR));

        assertThat(response.totalAmount()).isEqualByComparingTo("283.33");
    }

    @Test
    void 折抵金額大於小計時總額為零而不是負數() {
        Product a = product(PRODUCT_ID, 10, "80");
        stubCart(new CartItem(member(), a, 1));
        stubProductLock(a);
        stubOrderSaveAssignsId();
        stubNoOrderItems();
        Coupon coupon = coupon(CouponType.CASH, "100"); // 折抵大於小計
        stubHoldsCoupon(coupon, heldCoupon(coupon));

        OrderDetailResponse response = service.createOrderFromCart(MEMBER_ID,
                new CreateOrderRequest(COUPON_CODE, null, null, RECEIVER_ADDR));

        assertThat(response.totalAmount()).isEqualByComparingTo("0");
    }

    // === 優惠券核銷規則 ========================================================

    @Test
    void 優惠券代碼不存在回404() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));
        stubProductLock(a);
        when(coupons.findByCode(COUPON_CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID,
                new CreateOrderRequest(COUPON_CODE, null, null, RECEIVER_ADDR)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 已停用或不在使用期限內的優惠券擋下() {
        Product a = product(PRODUCT_ID, 10, "1000");

        Coupon disabled = coupon(CouponType.CASH, "100");
        disabled.setStatus(CouponStatus.INACTIVE);
        assertCouponRejected(a, disabled, "失效");

        Coupon notStarted = coupon(CouponType.CASH, "100");
        notStarted.setStartDate(LocalDate.now().plusDays(1)); // 還沒開始
        assertCouponRejected(a, notStarted, "失效");

        Coupon expired = coupon(CouponType.CASH, "100");
        expired.setEndDate(LocalDate.now().minusDays(1)); // 已過期
        assertCouponRejected(a, expired, "失效");
    }

    @Test
    void 券的適用品項與購買類型不符時擋下() {
        Product a = product(PRODUCT_ID, 10, "1000");
        Coupon courseOnly = coupon(CouponType.CASH, "100");
        courseOnly.setApplicableType(CouponApplicableType.COURSE); // 只能用在課程，這裡買商品

        assertCouponRejected(a, courseOnly, "不適用");
    }

    @Test
    void 未達最低消費金額擋下() {
        Product a = product(PRODUCT_ID, 10, "1000");
        Coupon highMinSpend = coupon(CouponType.CASH, "100");
        highMinSpend.setMinSpend(new BigDecimal("2000")); // 小計 1000 < 低消 2000

        assertCouponRejected(a, highMinSpend, "最低消費");
    }

    @Test
    void 未持有或已使用過該券時擋下() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));
        stubProductLock(a);
        Coupon coupon = coupon(CouponType.CASH, "100");
        when(coupons.findByCode(COUPON_CODE)).thenReturn(Optional.of(coupon));
        // 查不到 UNUSED 的持有紀錄＝沒領過或已用掉
        when(memberCoupons.findByMemberIdAndCouponCouponIdAndStatus(
                MEMBER_ID, COUPON_ID, MemberCouponStatus.UNUSED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID,
                new CreateOrderRequest(COUPON_CODE, null, null, RECEIVER_ADDR)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("尚未持有");

        verify(orders, never()).save(any());
    }

    private void assertCouponRejected(Product product, Coupon coupon, String messageFragment) {
        stubCart(new CartItem(member(), product, 1));
        stubProductLock(product);
        stubHoldsCoupon(coupon, heldCoupon(coupon));

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID,
                new CreateOrderRequest(COUPON_CODE, null, null, RECEIVER_ADDR)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining(messageFragment);
    }

    // === previewFromCart()：結帳前試算 ========================================

    @Test
    void 試算_無優惠券時折抵為零應付等於小計() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 2));

        OrderPreviewResponse preview = service.previewFromCart(MEMBER_ID, null);

        assertThat(preview.subtotalAmount()).isEqualByComparingTo("2000");
        assertThat(preview.discountAmount()).isEqualByComparingTo("0");
        assertThat(preview.totalAmount()).isEqualByComparingTo("2000");
        assertThat(preview.couponApplied()).isFalse();
    }

    @Test
    void 試算_現金券折抵並標記已套用() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));
        Coupon coupon = coupon(CouponType.CASH, "100");
        MemberCoupon held = heldCoupon(coupon);
        stubHoldsCoupon(coupon, held);

        OrderPreviewResponse preview = service.previewFromCart(MEMBER_ID, COUPON_CODE);

        assertThat(preview.couponApplied()).isTrue();
        assertThat(preview.discountAmount()).isEqualByComparingTo("100");
        assertThat(preview.totalAmount()).isEqualByComparingTo("900");
        assertThat(preview.couponCode()).isEqualTo(COUPON_CODE);
        // 試算不得消耗優惠券
        assertThat(held.getStatus()).isEqualTo(MemberCouponStatus.UNUSED);
        assertThat(held.getUsedAt()).isNull();
        verify(orders, never()).save(any());
    }

    @Test
    void 試算_百分比券四捨五入() {
        Product a = product(PRODUCT_ID, 10, "333.33");
        stubCart(new CartItem(member(), a, 1));
        Coupon coupon = coupon(CouponType.PERCENT, "15"); // 333.33 × 15% = 49.9995 → 50.00
        stubHoldsCoupon(coupon, heldCoupon(coupon));

        OrderPreviewResponse preview = service.previewFromCart(MEMBER_ID, COUPON_CODE);

        assertThat(preview.discountAmount()).isEqualByComparingTo("50.00");
        assertThat(preview.totalAmount()).isEqualByComparingTo("283.33");
    }

    @Test
    void 試算_券不適用時以200回傳提示訊息且不變更持有紀錄() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));
        Coupon highMinSpend = coupon(CouponType.CASH, "100");
        highMinSpend.setMinSpend(new BigDecimal("2000"));
        MemberCoupon held = heldCoupon(highMinSpend);
        stubHoldsCoupon(highMinSpend, held);

        OrderPreviewResponse preview = service.previewFromCart(MEMBER_ID, COUPON_CODE);

        assertThat(preview.couponApplied()).isFalse();
        assertThat(preview.couponMessage()).contains("最低消費");
        assertThat(preview.discountAmount()).isEqualByComparingTo("0");
        assertThat(preview.totalAmount()).isEqualByComparingTo("1000");
        assertThat(held.getStatus()).isEqualTo(MemberCouponStatus.UNUSED);
    }

    @Test
    void 試算_查無優惠券代碼時回couponApplied為false() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));
        when(coupons.findByCode(COUPON_CODE)).thenReturn(Optional.empty());

        OrderPreviewResponse preview = service.previewFromCart(MEMBER_ID, COUPON_CODE);

        assertThat(preview.couponApplied()).isFalse();
        assertThat(preview.couponMessage()).contains("查無");
        assertThat(preview.totalAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void 試算_未持有該券時回couponApplied為false() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));
        Coupon coupon = coupon(CouponType.CASH, "100");
        when(coupons.findByCode(COUPON_CODE)).thenReturn(Optional.of(coupon));
        when(memberCoupons.findByMemberIdAndCouponCouponIdAndStatus(
                MEMBER_ID, COUPON_ID, MemberCouponStatus.UNUSED)).thenReturn(Optional.empty());

        OrderPreviewResponse preview = service.previewFromCart(MEMBER_ID, COUPON_CODE);

        assertThat(preview.couponApplied()).isFalse();
        assertThat(preview.couponMessage()).contains("尚未持有");
    }

    // === 發票資訊 ============================================================

    @Test
    void 結帳帶手機條碼載具時儲存於訂單() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));
        stubProductLock(a);
        stubOrderSaveAssignsId();
        stubNoOrderItems();

        service.createOrderFromCart(MEMBER_ID, new CreateOrderRequest(null, null, null, RECEIVER_ADDR,
                InvoiceType.MOBILE_BARCODE, "/ABC1234", null, null));

        org.mockito.ArgumentCaptor<Order> saved = org.mockito.ArgumentCaptor.forClass(Order.class);
        verify(orders).save(saved.capture());
        assertThat(saved.getValue().getInvoiceType()).isEqualTo(InvoiceType.MOBILE_BARCODE);
        assertThat(saved.getValue().getInvoiceCarrier()).isEqualTo("/ABC1234");
    }

    @Test
    void 結帳帶格式錯誤的手機條碼載具時擋下且不建單() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID, new CreateOrderRequest(
                null, null, null, RECEIVER_ADDR, InvoiceType.MOBILE_BARCODE, "abc", null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("手機條碼");

        verify(orders, never()).save(any());
    }

    @Test
    void 結帳選公司戶但未帶統編時擋下() {
        Product a = product(PRODUCT_ID, 10, "1000");
        stubCart(new CartItem(member(), a, 1));

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID, new CreateOrderRequest(
                null, null, null, RECEIVER_ADDR, InvoiceType.COMPANY, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("統一編號");

        verify(orders, never()).save(any());
    }

    // === createDirectOrder()：私教課 ==========================================

    /** 私教課需先持有有效訂閱才能購買（requireActiveSubscription），成功路徑都要先備妥 */
    private void stubHasActiveSubscription() {
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                any(), any(), any())).thenReturn(Optional.of(new MemberSubscription()));
    }

    @Test
    void 私教課直接結帳以單價乘堂數計算且不碰庫存() {
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course("3000", 10)));
        stubHasActiveSubscription();
        stubOrderSaveAssignsId();
        stubNoOrderItems();

        OrderDetailResponse response = service.createDirectOrder(MEMBER_ID,
                new DirectCheckoutRequest(COURSE_ID, 2, null));

        assertThat(response.totalAmount()).isEqualByComparingTo("6000");
        verify(products, never()).findByIdForUpdate(any());
    }

    @Test
    void 私教課數量為零或null擋下() {
        assertThatThrownBy(() -> service.createDirectOrder(MEMBER_ID,
                new DirectCheckoutRequest(COURSE_ID, 0, null)))
                .isInstanceOf(InvalidQuantityException.class);
        assertThatThrownBy(() -> service.createDirectOrder(MEMBER_ID,
                new DirectCheckoutRequest(COURSE_ID, null, null)))
                .isInstanceOf(InvalidQuantityException.class);

        verify(members, never()).findById(any());
    }

    @Test
    void 私教課不存在回404() {
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(courses.findById(COURSE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDirectOrder(MEMBER_ID,
                new DirectCheckoutRequest(COURSE_ID, 1, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 下架的私教課不可直接結帳() {
        PrivateCourse inactive = course("3000", 10);
        inactive.setStatus(CourseStatus.INACTIVE);
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.createDirectOrder(MEMBER_ID,
                new DirectCheckoutRequest(COURSE_ID, 1, null)))
                .isInstanceOf(DirectCheckoutNotAllowedException.class);

        verify(orders, never()).save(any());
    }

    // === createSubscriptionOrder()：會員訂閱 ===================================

    @Test
    void 訂閱結帳成功並記錄方案() {
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(subscriptionPlans.findById(PLAN_ID)).thenReturn(Optional.of(plan("1500", 30)));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        stubOrderSaveAssignsId();
        stubNoOrderItems();

        OrderDetailResponse response = service.createSubscriptionOrder(MEMBER_ID,
                new SubscriptionCheckoutRequest(PLAN_ID, null));

        assertThat(response.totalAmount()).isEqualByComparingTo("1500");
        verify(orders).save(any(Order.class));
    }

    @Test
    void 已下架的訂閱方案不可購買() {
        SubscriptionPlan inactive = plan("1500", 30);
        inactive.setStatus("INACTIVE");
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(subscriptionPlans.findById(PLAN_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.createSubscriptionOrder(MEMBER_ID,
                new SubscriptionCheckoutRequest(PLAN_ID, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("無法購買");

        verify(orders, never()).save(any());
    }

    @Test
    void 名下有未到期訂閱時不可提前續約() {
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(subscriptionPlans.findById(PLAN_ID)).thenReturn(Optional.of(plan("1500", 30)));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                any(), any(), any())).thenReturn(Optional.of(new MemberSubscription()));

        assertThatThrownBy(() -> service.createSubscriptionOrder(MEMBER_ID,
                new SubscriptionCheckoutRequest(PLAN_ID, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("到期後才能購買");

        verify(orders, never()).save(any());
    }

    // === cancelOrder()：未付款 vs 已付款的分流 ==================================

    @Test
    void 取消未付款訂單還原庫存與優惠券() {
        Order order = savedOrder();
        Coupon coupon = coupon(CouponType.CASH, "100");
        MemberCoupon used = heldCoupon(coupon);
        used.setStatus(MemberCouponStatus.USED);
        used.setUsedAt(LocalDateTime.now());
        ReflectionTestUtils.setField(order, "memberCoupon", used);

        Product product = product(PRODUCT_ID, 5, "1000");
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.of(order));
        when(orderItems.findAllByOrderIdOrderByIdAsc(ORDER_ID))
                .thenReturn(List.of(productItem(order, product, 3, 1)));
        stubProductLock(product);

        OrderDetailResponse response = service.cancelOrder(MEMBER_ID, ORDER_ID, new CancelOrderRequest("不想要了"));

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(product.getStock()).isEqualTo(8); // 5 + 3 還原
        assertThat(used.getStatus()).isEqualTo(MemberCouponStatus.UNUSED);
        assertThat(used.getUsedAt()).isNull();
        verify(paymentRefunds, never()).save(any());
    }

    @Test
    void 取消未付款訂單時課程明細不還原庫存() {
        Order order = savedOrder();
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.of(order));
        when(orderItems.findAllByOrderIdOrderByIdAsc(ORDER_ID))
                .thenReturn(List.of(courseItem(order, course("3000", 10), 1, 1)));

        service.cancelOrder(MEMBER_ID, ORDER_ID, null);

        // 課程明細沒有商品可還原，不應該去鎖任何商品
        verify(products, never()).findByIdForUpdate(any());
    }

    // === expireStaleUnpaidOrders()：逾時未付款訂單自動取消 ======================

    @Test
    void 逾時未付款訂單自動取消並還原庫存與優惠券() {
        Order order = savedOrder();
        MemberCoupon used = heldCoupon(coupon(CouponType.CASH, "100"));
        used.setStatus(MemberCouponStatus.USED);
        ReflectionTestUtils.setField(order, "memberCoupon", used);
        Product product = product(PRODUCT_ID, 5, "1000");
        when(orders.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.UNPAID), any()))
                .thenReturn(List.of(order));
        when(orderItems.findAllByOrderIdOrderByIdAsc(ORDER_ID))
                .thenReturn(List.of(productItem(order, product, 3, 1)));
        stubProductLock(product);

        int cancelled = service.expireStaleUnpaidOrders(java.time.Duration.ofMinutes(30));

        assertThat(cancelled).isEqualTo(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(product.getStock()).isEqualTo(8); // 5 + 3 還原
        assertThat(used.getStatus()).isEqualTo(MemberCouponStatus.UNUSED);
        verify(orderStatusHistories).save(org.mockito.ArgumentMatchers.argThat(
                (OrderStatusHistory h) -> "CANCEL".equals(h.getNote()) && "SYSTEM".equals(h.getChangeSource())));
    }

    @Test
    void 沒有逾時訂單時不做任何事() {
        when(orders.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.UNPAID), any())).thenReturn(List.of());

        assertThat(service.expireStaleUnpaidOrders(java.time.Duration.ofMinutes(30))).isZero();
        verify(orderStatusHistories, never()).save(any());
    }

    @Test
    void 取消已付款訂單改為建立待處理退款且訂單維持已付款() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        PaymentTransaction payment = payment(order);
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.of(order));
        when(paymentTransactions.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        stubNoOrderItems();

        OrderDetailResponse response = service.cancelOrder(MEMBER_ID, ORDER_ID, new CancelOrderRequest("買錯了"));

        // 關鍵：狀態還不是 REFUNDED，要等後台實際完成退款
        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        verify(paymentRefunds).save(any(PaymentRefund.class));
        // 提前釋放庫存會造成競態，這裡不還原
        verify(products, never()).findByIdForUpdate(any());
    }

    @Test
    void 已出貨訂單申請退款建立待處理退款且訂單維持已出貨() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        order.markShipped();
        PaymentTransaction payment = payment(order);
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.of(order));
        when(paymentTransactions.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        stubNoOrderItems();

        OrderDetailResponse response = service.cancelOrder(MEMBER_ID, ORDER_ID, new CancelOrderRequest("商品有瑕疵"));

        assertThat(response.status()).isEqualTo(OrderStatus.SHIPPED);
        verify(paymentRefunds).save(any(PaymentRefund.class));
    }

    @Test
    void 已完成訂單也可以申請退款() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        order.markShipped();
        // 這筆是實體商品訂單（上一行已 markShipped），依「含實體商品須先出貨」規則帶 true
        order.markCompleted(true);
        PaymentTransaction payment = payment(order);
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.of(order));
        when(paymentTransactions.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        stubNoOrderItems();

        OrderDetailResponse response = service.cancelOrder(MEMBER_ID, ORDER_ID, null);

        assertThat(response.status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void 已付款訂單找不到付款紀錄時回404() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.of(order));
        when(paymentTransactions.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOrder(MEMBER_ID, ORDER_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(paymentRefunds, never()).save(any());
    }

    @Test
    void 取消他人訂單回404() {
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOrder(MEMBER_ID, ORDER_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 重複取消已取消的訂單擋下() {
        Order order = savedOrder();
        order.cancel("第一次取消");
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(MEMBER_ID, ORDER_ID, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("只有未付款的訂單可以取消");
    }

    // === completeRefund() ====================================================

    @Test
    void 完成退款還原庫存與優惠券並轉為已退款且發佈事件() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        Coupon coupon = coupon(CouponType.CASH, "100");
        MemberCoupon used = heldCoupon(coupon);
        used.setStatus(MemberCouponStatus.USED);
        ReflectionTestUtils.setField(order, "memberCoupon", used);

        PaymentTransaction payment = payment(order);
        PaymentRefund refund = new PaymentRefund(payment, "RF1", new BigDecimal("1000"), "退款");
        refund.setStatus("PENDING");
        ReflectionTestUtils.setField(refund, "id", 7);

        Product product = product(PRODUCT_ID, 2, "1000");
        when(paymentRefunds.findById(7)).thenReturn(Optional.of(refund));
        when(orderItems.findAllByOrderIdOrderByIdAsc(ORDER_ID))
                .thenReturn(List.of(productItem(order, product, 3, 1)));
        stubProductLock(product);

        service.completeRefund(7);

        assertThat(refund.getStatus()).isEqualTo("COMPLETED");
        assertThat(refund.getCompletedAt()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(product.getStock()).isEqualTo(5);
        assertThat(used.getStatus()).isEqualTo(MemberCouponStatus.UNUSED);
        verify(events).publishEvent(any(RefundCompletedEvent.class));
    }

    @Test
    void 重複完成同一筆退款擋下() {
        PaymentTransaction payment = payment(savedOrder());
        PaymentRefund refund = new PaymentRefund(payment, "RF1", new BigDecimal("1000"), "退款");
        refund.setStatus("COMPLETED"); // 已經處理過
        when(paymentRefunds.findById(7)).thenReturn(Optional.of(refund));

        assertThatThrownBy(() -> service.completeRefund(7))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已處理過");
    }

    @Test
    void 退款紀錄不存在回404() {
        when(paymentRefunds.findById(7)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeRefund(7))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 完成已出貨訂單的退款一樣還原庫存並轉為已退款() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        order.markShipped();

        PaymentTransaction payment = payment(order);
        PaymentRefund refund = new PaymentRefund(payment, "RF1", new BigDecimal("1000"), "商品有瑕疵");
        refund.setStatus("PENDING");
        ReflectionTestUtils.setField(refund, "id", 7);

        Product product = product(PRODUCT_ID, 2, "1000");
        when(paymentRefunds.findById(7)).thenReturn(Optional.of(refund));
        when(orderItems.findAllByOrderIdOrderByIdAsc(ORDER_ID))
                .thenReturn(List.of(productItem(order, product, 3, 1)));
        stubProductLock(product);

        service.completeRefund(7);

        assertThat(refund.getStatus()).isEqualTo("COMPLETED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(product.getStock()).isEqualTo(5);
    }

    // === rejectRefund() =========================================================

    @Test
    void 駁回退款申請後訂單狀態不變且退款轉為已駁回() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        PaymentTransaction payment = payment(order);
        PaymentRefund refund = new PaymentRefund(payment, "RF1", new BigDecimal("1000"), "不想要了");
        refund.setStatus("PENDING");
        ReflectionTestUtils.setField(refund, "id", 7);
        when(paymentRefunds.findById(7)).thenReturn(Optional.of(refund));

        service.rejectRefund(7, "已超過鑑賞期");

        assertThat(refund.getStatus()).isEqualTo("REJECTED");
        assertThat(refund.getCompletedAt()).isNotNull();
        assertThat(refund.getReason()).isEqualTo("不想要了｜駁回原因：已超過鑑賞期");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID); // 訂單狀態不受駁回影響
        verify(orderStatusHistories).save(any());
    }

    @Test
    void 駁回退款沒有填理由時不動原本的申請理由() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        PaymentTransaction payment = payment(order);
        PaymentRefund refund = new PaymentRefund(payment, "RF1", new BigDecimal("1000"), "不想要了");
        refund.setStatus("PENDING");
        ReflectionTestUtils.setField(refund, "id", 7);
        when(paymentRefunds.findById(7)).thenReturn(Optional.of(refund));

        service.rejectRefund(7, null);

        assertThat(refund.getReason()).isEqualTo("不想要了");
    }

    @Test
    void 重複駁回已處理過的退款擋下() {
        PaymentTransaction payment = payment(savedOrder());
        PaymentRefund refund = new PaymentRefund(payment, "RF1", new BigDecimal("1000"), "退款");
        refund.setStatus("COMPLETED");
        when(paymentRefunds.findById(7)).thenReturn(Optional.of(refund));

        assertThatThrownBy(() -> service.rejectRefund(7, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已處理過");
    }

    @Test
    void 駁回不存在的退款回404() {
        when(paymentRefunds.findById(7)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rejectRefund(7, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === shipOrder() / completeOrder() ========================================

    @Test
    void 已付款訂單標記出貨後轉為已出貨並寫入時間軸() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        stubNoOrderItems();

        OrderDetailResponse response = service.shipOrder(ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.SHIPPED);
        verify(orderStatusHistories).save(any());
    }

    @Test
    void 未付款訂單不可標記出貨() {
        Order order = savedOrder();
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.shipOrder(ORDER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已付款");
    }

    @Test
    void 已出貨訂單標記完成後轉為已完成() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        order.markShipped();
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        stubNoOrderItems();

        OrderDetailResponse response = service.completeOrder(ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void 已付款訂單可以不經出貨直接標記完成() {
        // 課程/訂閱訂單沒有出貨步驟，PAID 可以直接轉 COMPLETED
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        stubNoOrderItems();

        OrderDetailResponse response = service.completeOrder(ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void 未付款訂單不可標記完成() {
        Order order = savedOrder();
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.completeOrder(ORDER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已出貨或已付款");
    }

    @Test
    void 有待處理退款的訂單不可標記出貨() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(paymentRefunds.existsByPaymentOrderIdAndStatus(ORDER_ID, "PENDING")).thenReturn(true);

        assertThatThrownBy(() -> service.shipOrder(ORDER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("退款申請正在處理中");

        verify(orderStatusHistories, never()).save(any());
    }

    @Test
    void 有待處理退款的訂單不可標記完成() {
        Order order = savedOrder();
        order.markPaid("Credit", "TRADE123");
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(paymentRefunds.existsByPaymentOrderIdAndStatus(ORDER_ID, "PENDING")).thenReturn(true);

        assertThatThrownBy(() -> service.completeOrder(ORDER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("退款申請正在處理中");

        verify(orderStatusHistories, never()).save(any());
    }

    @Test
    void 出貨找不到訂單回404() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.shipOrder(ORDER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 完成找不到訂單回404() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeOrder(ORDER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === fulfillPaidOrder()：付款後履約 ========================================

    @Test
    void 付款後課程明細依堂數換算發放額度() {
        Order order = savedOrder();
        PrivateCourse course = course("3000", 10); // 每單位 10 堂
        when(orderItems.findAllByOrderIdOrderByIdAsc(ORDER_ID))
                .thenReturn(List.of(courseItem(order, course, 2, 1))); // 買 2 單位

        service.fulfillPaidOrder(order);

        // 2 × 10 = 20 堂
        verify(courseQuotas).save(org.mockito.ArgumentMatchers.argThat(
                (MemberCourseQuota quota) -> quota.getRemainingCourse() == 20));
    }

    @Test
    void 付款後商品明細不發放課程額度() {
        Order order = savedOrder();
        when(orderItems.findAllByOrderIdOrderByIdAsc(ORDER_ID))
                .thenReturn(List.of(productItem(order, product(PRODUCT_ID, 5, "1000"), 1, 1)));

        service.fulfillPaidOrder(order);

        verify(courseQuotas, never()).save(any());
    }

    @Test
    void 付款後訂閱訂單啟用一期新訂閱並依方案天數計算到期日() {
        Order order = savedOrder();
        SubscriptionPlan plan = plan("1500", 30);
        order.setSubscriptionPlan(plan);
        stubNoOrderItems();

        service.fulfillPaidOrder(order);

        verify(subscriptions).save(org.mockito.ArgumentMatchers.argThat(
                (MemberSubscription sub) -> sub.getStatus() == SubscriptionStatus.ACTIVE
                        && sub.getEndDate().isAfter(sub.getStartDate())
                        && sub.getPlanName().equals("月費方案")));
    }

    @Test
    void 付款後純商品訂單不建立訂閱() {
        Order order = savedOrder();
        stubNoOrderItems();

        service.fulfillPaidOrder(order);

        verify(subscriptions, never()).save(any());
    }
}
