package tw.com.ispan.gym.service.order;



import tw.com.ispan.gym.dto.order.CancelOrderRequest;
import tw.com.ispan.gym.dto.order.CreateOrderRequest;
import tw.com.ispan.gym.dto.order.DirectCheckoutRequest;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderItemResponse;
import tw.com.ispan.gym.dto.order.PendingRefundResponse;
import tw.com.ispan.gym.dto.order.SubscriptionCheckoutRequest;
import tw.com.ispan.gym.entity.cart.CartItem;
import tw.com.ispan.gym.entity.coach.MemberCourseQuota;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.entity.order.OrderStatusHistory;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.payment.PaymentRefund;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.member.Member;
// 【合併時的修改說明】MemberCoupon／MemberSubscription 實體與其 repository 依
// feat/coupon-controller 的分包設計搬到 member 套件（會員名下的資料歸 member），
// SubscriptionRepository 同時更名為 MemberSubscriptionRepository 與實體對齊，
// 這裡只有 import 路徑與型別名稱跟著改，訂單邏輯本身沒動。
import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.coupon.CouponType;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.enums.product.ProductStatus;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.DirectCheckoutNotAllowedException;
import tw.com.ispan.gym.exception.InsufficientStockException;
import tw.com.ispan.gym.exception.InvalidQuantityException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.exception.SubscriptionRequiredException;
import tw.com.ispan.gym.exception.product.ProductNotFoundException;
import tw.com.ispan.gym.exception.product.ProductUnavailableException;
import tw.com.ispan.gym.repository.cart.CartItemRepository;
import tw.com.ispan.gym.repository.coach.MemberCourseQuotaRepository;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderStatusHistoryRepository;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;
import tw.com.ispan.gym.repository.payment.PaymentRefundRepository;
import tw.com.ispan.gym.repository.payment.PaymentTransactionRepository;
import tw.com.ispan.gym.repository.product.ProductRepository;
import tw.com.ispan.gym.repository.member.MemberCouponRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.subscription.SubscriptionPlanRepository;
import tw.com.ispan.gym.repository.member.MemberSubscriptionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.event.order.RefundCompletedEvent;

/**
 * 購物車結帳（{@link #createOrderFromCart}）用於一般商品；課程與會員訂閱則分別透過
 * {@link #createDirectOrder}／{@link #createSubscriptionOrder} 略過購物車直接結帳。
 * {@link #cancelOrder} 依訂單狀態分流：未付款直接取消並還原庫存/優惠券；已付款則建立
 * 待處理退款（{@link PaymentRefund}），實際退款與訂單狀態轉移由後台呼叫
 * {@link #completeRefund} 完成。課程/訂閱的履約（發放堂數額度、啟用訂閱）
 * 由 {@link #fulfillPaidOrder} 在金流付款成功後觸發，見 EcpayService。訂閱不允許提前續約，
 * 名下有未到期的有效訂閱時 {@link #createSubscriptionOrder} 會直接擋下結帳請求；反過來，
 * 名下沒有有效訂閱時 {@link #createDirectOrder} 也會擋下私教課購買，見 {@link #requireActiveSubscription}。
 */
@Service
@Transactional(readOnly = true)
public class OrderService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TRADE_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final PrivateCourseRepository courses;
    private final MemberRepository members;
    private final CouponRepository coupons;
    private final MemberCouponRepository memberCoupons;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderStatusHistoryRepository orderStatusHistories;
    private final SubscriptionPlanRepository subscriptionPlans;
    private final MemberSubscriptionRepository subscriptions;
    private final MemberCourseQuotaRepository courseQuotas;
    private final PaymentTransactionRepository paymentTransactions;
    private final PaymentRefundRepository paymentRefunds;
    private final ApplicationEventPublisher events;

    public OrderService(CartItemRepository cartItems, ProductRepository products, PrivateCourseRepository courses,
            MemberRepository members, CouponRepository coupons, MemberCouponRepository memberCoupons,
            OrderRepository orders, OrderItemRepository orderItems,
            OrderStatusHistoryRepository orderStatusHistories, SubscriptionPlanRepository subscriptionPlans,
            MemberSubscriptionRepository subscriptions, MemberCourseQuotaRepository courseQuotas,
            PaymentTransactionRepository paymentTransactions, PaymentRefundRepository paymentRefunds,
            ApplicationEventPublisher events) {
        this.cartItems = cartItems;
        this.products = products;
        this.courses = courses;
        this.members = members;
        this.coupons = coupons;
        this.memberCoupons = memberCoupons;
        this.orders = orders;
        this.orderItems = orderItems;
        this.orderStatusHistories = orderStatusHistories;
        this.subscriptionPlans = subscriptionPlans;
        this.subscriptions = subscriptions;
        this.courseQuotas = courseQuotas;
        this.paymentTransactions = paymentTransactions;
        this.paymentRefunds = paymentRefunds;
        this.events = events;
    }

    @Transactional
    public OrderDetailResponse createOrderFromCart(Integer memberId, CreateOrderRequest request) {
        List<CartItem> items = cartItems.findAllByMemberIdOrderByCreatedAtAsc(memberId);
        if (items.isEmpty()) {
            throw new BusinessRuleException("購物車是空的，無法建立訂單");
        }

        Member member = members.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        List<ProductQuantity> lines = items.stream()
                .map(ci -> new ProductQuantity(ci.getProduct(), ci.getQuantity()))
                .toList();
        String couponCode = request == null ? null : request.couponCode();
        String receiverName = request == null ? null : request.receiverName();
        String receiverPhone = request == null ? null : request.receiverPhone();
        String receiverAddress = request == null ? null : request.receiverAddress();

        Order order = createOrder(member, lines, couponCode, receiverName, receiverPhone, receiverAddress,
                CouponApplicableType.PRODUCT);
        cartItems.deleteAllByMemberId(memberId);
        return toDetailResponse(order);
    }

    /**
     * 私教課略過購物車，直接以 privateCourseId + quantity 結帳（團課憑訂閱參加，不走購買）。
     * 會員訂閱請改用 {@link #createSubscriptionOrder}。
     * 需先持有未到期的有效訂閱才能購買，見 {@link #requireActiveSubscription}。
     */
    @Transactional
    public OrderDetailResponse createDirectOrder(Integer memberId, DirectCheckoutRequest request) {
        if (request.quantity() == null || request.quantity() < 1) {
            throw new InvalidQuantityException();
        }
        Member member = members.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        PrivateCourse course = courses.findById(request.privateCourseId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到私教課：" + request.privateCourseId()));
        if (!CourseStatus.ACTIVE.equals(course.getStatus())) {
            throw new DirectCheckoutNotAllowedException(course.getId());
        }
        requireActiveSubscription(memberId);

        Order order = createCourseOrder(member, course, request.quantity(), request.couponCode());
        return toDetailResponse(order);
    }

    /**
     * 私教課解鎖條件：會員名下需有一筆未到期的 ACTIVE 訂閱，複用防重複訂閱同一支查詢，
     * 語意對稱——「有 → 不能再訂閱」「沒有 → 不能買私教課」。只卡購買當下，
     * 訂閱到期後先前已購買的堂數仍可正常預約使用。
     */
    private void requireActiveSubscription(Integer memberId) {
        subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                memberId, LocalDateTime.now(TAIPEI), SubscriptionStatus.ACTIVE)
                .orElseThrow(SubscriptionRequiredException::new);
    }

    /**
     * 私教課訂單：不涉庫存，明細以 private_course_id 記錄。
     * 履約（發放堂數額度）待付款成功後由 {@link #fulfillPaidOrder} 執行。
     */
    private Order createCourseOrder(Member member, PrivateCourse course, int quantity, String couponCode) {
        BigDecimal subtotal = course.getPrice().multiply(BigDecimal.valueOf(quantity));

        MemberCoupon memberCoupon = null;
        BigDecimal discount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            Coupon coupon = coupons.findByCode(couponCode)
                    .orElseThrow(() -> new ResourceNotFoundException("找不到優惠券：" + couponCode));
            memberCoupon = redeemCoupon(member, coupon, subtotal, CouponApplicableType.COURSE);
            discount = computeDiscount(coupon, subtotal);
        }

        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO);
        Order order = orders.save(new Order(member, generateMerchantTradeNo(), generateOrderNo(), subtotal, discount,
                subtotal, total, memberCoupon, member.getFirstName() + member.getLastName(), member.getPhone(), null,
                member.getAccount().getEmail()));

        orderItems.save(new OrderItem(order, course, course.getName(), course.getPrice(), quantity));
        orderStatusHistories.save(new OrderStatusHistory(order, null, order.getStatus().name(), member, "USER",
                "CHECKOUT"));
        return order;
    }

    /**
     * 會員訂閱略過購物車，直接以 subscriptionPlanId 結帳。訂閱無數量概念，一次買一個方案週期。
     * 不允許提前續約：會員名下還有未到期的有效訂閱時直接擋在結帳這一步，等到期後才能再買下一期，
     * 避免付款成功後 {@link #fulfillPaidOrder} 還要處理「新舊訂閱重疊」的狀態。
     */
    @Transactional
    public OrderDetailResponse createSubscriptionOrder(Integer memberId, SubscriptionCheckoutRequest request) {
        Member member = members.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        SubscriptionPlan plan = subscriptionPlans.findById(request.subscriptionPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂閱方案：" + request.subscriptionPlanId()));
        if (!"ACTIVE".equals(plan.getStatus())) {
            throw new BusinessRuleException("此訂閱方案目前無法購買");
        }
        if (subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                memberId, LocalDateTime.now(TAIPEI), SubscriptionStatus.ACTIVE).isPresent()) {
            throw new BusinessRuleException("尚有未到期的訂閱，到期後才能購買下一期");
        }

        Order order = createSubscriptionOrderInternal(member, plan, request.couponCode());
        return toDetailResponse(order);
    }

    private Order createSubscriptionOrderInternal(Member member, SubscriptionPlan plan, String couponCode) {
        BigDecimal subtotal = plan.getPrice();

        MemberCoupon memberCoupon = null;
        BigDecimal discount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            Coupon coupon = coupons.findByCode(couponCode)
                    .orElseThrow(() -> new ResourceNotFoundException("找不到優惠券：" + couponCode));
            memberCoupon = redeemCoupon(member, coupon, subtotal, CouponApplicableType.SUBSCRIPTION);
            discount = computeDiscount(coupon, subtotal);
        }

        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO);
        Order order = new Order(member, generateMerchantTradeNo(), generateOrderNo(), subtotal, discount,
                subtotal, total, memberCoupon, member.getFirstName() + member.getLastName(), member.getPhone(), null,
                member.getAccount().getEmail());
        order.setSubscriptionPlan(plan);
        order = orders.save(order);

        orderStatusHistories.save(new OrderStatusHistory(order, null, order.getStatus().name(), member, "USER",
                "CHECKOUT"));
        return order;
    }

    /**
     * 付款成功後的履約：課程明細發放堂數額度、訂閱訂單啟用新一期訂閱。
     * 商品已在建單當下扣庫存，這裡不需再處理。由 EcpayService 在確認付款成功時呼叫。
     */
    @Transactional
    public void fulfillPaidOrder(Order order) {
        for (OrderItem item : orderItems.findAllByOrderIdOrderByIdAsc(order.getId())) {
            if (item.isCourseItem()) {
                grantCourseQuota(order, item);
            }
        }
        if (order.getSubscriptionPlan() != null) {
            activateSubscription(order);
        }
    }

    private void grantCourseQuota(Order order, OrderItem item) {
        PrivateCourse course = item.getCourse();
        MemberCourseQuota quota = new MemberCourseQuota();
        quota.setMember(order.getMember());
        quota.setCourse(course);
        quota.setTrainer(course.getTrainer());
        quota.setOrderItem(item);
        quota.setCourseName(course.getName());
        quota.setRemainingCourse(Math.toIntExact(item.getQuantity() * course.getSessionPerUnit()));
        courseQuotas.save(quota);
    }

    /** 續約政策：不允許提前續約（見 createSubscriptionOrder 的檢查），付款成功時一律開新一期訂閱 */
    private void activateSubscription(Order order) {
        SubscriptionPlan plan = order.getSubscriptionPlan();
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        MemberSubscription subscription = new MemberSubscription();
        subscription.setMember(order.getMember());
        subscription.setOrder(order);
        subscription.setPlanName(plan.getName());
        subscription.setSubscriptionPlan(plan);
        subscription.setStartDate(now);
        subscription.setEndDate(now.plusDays(plan.getDurationDays()));
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscriptions.save(subscription);
    }

    /**
     * 依訂單狀態分流：未付款直接取消（還原庫存、退回優惠券）；已付款則建立一筆待處理退款，
     * 訂單狀態維持 PAID 直到後台呼叫 {@link #completeRefund} 實際完成退款——
     * 提前釋放庫存/優惠券會造成「退款還沒真的處理、商品已經被別人買走」的競態。
     */
    @Transactional
    public OrderDetailResponse cancelOrder(Integer memberId, Integer orderId, CancelOrderRequest request) {
        Order order = orders.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        String fromStatus = order.getStatus().name();
        String reason = request == null ? null : request.reason();

        if (order.getStatus() == OrderStatus.PAID) {
            requestRefund(order, reason);
            orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(),
                    order.getMember(), "USER", "REFUND_REQUEST"));
            return toDetailResponse(order);
        }

        order.cancel(reason);
        restoreStock(order);
        restoreCoupon(order);

        orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(), order.getMember(),
                "USER", "CANCEL"));
        return toDetailResponse(order);
    }

    private void requestRefund(Order order, String reason) {
        order.assertRefundable();
        PaymentTransaction payment = paymentTransactions.findByOrderId(order.getId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到這筆訂單的付款紀錄"));
        // assertRefundable 只看訂單狀態（PAID），沒擋「同一筆訂單已經有退款申請在跑」——
        // 使用者重複按申請退款會疊出好幾筆 PENDING PaymentRefund，這裡補上防呆
        if (paymentRefunds.existsByPaymentIdAndStatus(payment.getId(), "PENDING")) {
            throw new BusinessRuleException("這筆訂單已經有退款申請正在處理中，請等候客服審核");
        }
        paymentRefunds.save(new PaymentRefund(payment, generateRefundNo(), order.getTotalAmount(), reason));
    }

    /**
     * 後台人工執行退款完成：退款狀態轉 COMPLETED、還原庫存/優惠券、訂單轉 REFUNDED。
     * 若訂單已經不是 PAID（同一訂單的重複退款申請，另一筆已經處理過），改標記這筆為 CANCELLED，不重複履約。
     */
    @Transactional
    public void completeRefund(Integer refundId) {
        PaymentRefund refund = paymentRefunds.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到退款紀錄：" + refundId));
        if (!"PENDING".equals(refund.getStatus())) {
            throw new BusinessRuleException("此退款已處理過");
        }
        Order order = refund.getPayment().getOrder();

        // 同一張訂單的重複退款申請（requestRefund 補防呆之前留下的舊資料才會出現）：
        // 訂單已經因為另一筆退款完成轉為 REFUNDED，這筆是多出來的重複申請，不能再走一次
        // 還原庫存/優惠券／訂閱，否則會錯誤地把庫存/優惠券還原兩次；直接標記為多餘即可，
        // 不計入 ReportRefundSummary（只統計 COMPLETED）
        if (order.getStatus() != OrderStatus.PAID) {
            refund.setStatus("CANCELLED");
            refund.setCompletedAt(LocalDateTime.now(TAIPEI));
            return;
        }

        String fromStatus = order.getStatus().name();

        refund.setStatus("COMPLETED");
        refund.setCompletedAt(LocalDateTime.now(TAIPEI));

        restoreStock(order);
        restoreCoupon(order);
        cancelSubscriptionIfAny(order);
        order.markRefunded();

        orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(), order.getMember(),
                "ADMIN", "REFUND_COMPLETE"));

        // 同步事件、同一交易：下游（如報表標記快照過時）與退款原子成敗
        if (order.getPaidAt() != null) {
            events.publishEvent(new RefundCompletedEvent(order.getId(), order.getPaidAt().toLocalDate()));
        }
    }

    /**
     * 後台標記已出貨：PAID → SHIPPED。僅適用於含實體商品的訂單，
     * 課程/訂閱訂單請直接用 {@link #completeOrder} 完成（無出貨步驟）。
     */
    @Transactional
    public OrderDetailResponse shipOrder(Integer orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        String fromStatus = order.getStatus().name();
        order.markShipped();
        orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(),
                order.getMember(), "ADMIN", "SHIP"));
        return toDetailResponse(order);
    }

    /** 後台標記訂單完成：SHIPPED → COMPLETED，或無出貨步驟的訂單直接 PAID → COMPLETED */
    @Transactional
    public OrderDetailResponse completeOrder(Integer orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        String fromStatus = order.getStatus().name();
        // 含實體商品就要求先出貨；私教課／訂閱沒有實體要寄，付款後可直接完成
        order.markCompleted(orderItems.existsByOrderIdAndProductIsNotNull(orderId));
        orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(),
                order.getMember(), "ADMIN", "COMPLETE"));
        return toDetailResponse(order);
    }

    /** 後台待處理退款清單，供人工執行退款前查看 */
    public List<PendingRefundResponse> listPendingRefunds() {
        return paymentRefunds.findByStatusOrderByRequestedAtAsc("PENDING").stream()
                .map(r -> new PendingRefundResponse(r.getId(), r.getPayment().getOrder().getId(),
                        r.getPayment().getOrder().getMerchantTradeNo(), r.getRefundNo(), r.getAmount(),
                        r.getReason(), r.getRequestedAt()))
                .toList();
    }

    /** 只有商品明細需要還原庫存；私教課明細（private_course_id 非空）不涉庫存 */
    private void restoreStock(Order order) {
        List<OrderItem> items = orderItems.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .filter(item -> !item.isCourseItem())
                .sorted(Comparator.comparing(item -> item.getProduct().getProductId()))
                .toList();
        for (OrderItem item : items) {
            Product product = reloadForUpdate(item.getProduct());
            product.setStock(Math.toIntExact(product.getStock() + item.getQuantity()));
        }
    }

    private void restoreCoupon(Order order) {
        MemberCoupon memberCoupon = order.getMemberCoupon();
        if (memberCoupon != null) {
            memberCoupon.setStatus(MemberCouponStatus.UNUSED);
            memberCoupon.setUsedAt(null);
        }
    }

    /**
     * 訂閱訂單退款時，該筆退款所啟用的那期訂閱要一併轉 CANCELLED，
     * 否則會員拿回退款後仍保有訂閱權限（含私教課購買資格）直到原訂到期日，形同漏洞。
     * 只有已付款成功才會有對應的 MemberSubscription（見 activateSubscription），
     * 查無資料代表退款的是一筆從未真正履約過的訂單，略過即可。
     */
    private void cancelSubscriptionIfAny(Order order) {
        if (order.getSubscriptionPlan() == null) {
            return;
        }
        subscriptions.findByOrderId(order.getId())
                .ifPresent(subscription -> subscription.setStatus(SubscriptionStatus.CANCELLED));
    }

    private Order createOrder(Member member, List<ProductQuantity> productLines, String couponCode,
            String receiverName, String receiverPhone, String receiverAddress, CouponApplicableType lineType) {
        // 依 productId 排序後才逐一鎖定，避免不同結帳請求互相持有對方需要的鎖而死結。
        List<OrderLine> lines = productLines.stream()
                .sorted(Comparator.comparing(pq -> pq.product().getProductId()))
                .map(pq -> new OrderLine(reloadForUpdate(pq.product()), pq.quantity()))
                .toList();
        for (OrderLine line : lines) {
            validateAvailableAndInStock(line.product(), line.quantity());
        }

        BigDecimal subtotal = lines.stream()
                .map(line -> line.product().getPrice().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        MemberCoupon memberCoupon = null;
        BigDecimal discount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            Coupon coupon = coupons.findByCode(couponCode)
                    .orElseThrow(() -> new ResourceNotFoundException("找不到優惠券：" + couponCode));
            memberCoupon = redeemCoupon(member, coupon, subtotal, lineType);
            discount = computeDiscount(coupon, subtotal);
        }

        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO);

        String finalReceiverName = valueOr(receiverName, member.getFirstName() + member.getLastName());
        String finalReceiverPhone = valueOr(receiverPhone, member.getPhone());

        Order order = new Order(member, generateMerchantTradeNo(), generateOrderNo(), subtotal, discount, subtotal,
                total, memberCoupon, finalReceiverName, finalReceiverPhone, receiverAddress, member.getAccount().getEmail());
        order = orders.save(order);

        for (OrderLine line : lines) {
            Product product = line.product();
            orderItems.save(new OrderItem(order, product, product.getName(), product.getPrice(), line.quantity()));
            product.setStock(Math.toIntExact(product.getStock() - line.quantity()));
        }

        orderStatusHistories.save(new OrderStatusHistory(order, null, order.getStatus().name(), member, "USER",
                "CHECKOUT"));
        return order;
    }

    private Product reloadForUpdate(Product product) {
        return products.findByIdForUpdate(product.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到商品：" + product.getProductId()));
    }

    private void validateAvailableAndInStock(Product product, int quantity) {
        if (!ProductStatus.ACTIVE.equals(product.getStatus())) {
            throw new ProductUnavailableException(product.getProductId());
        }
        if (quantity > product.getStock()) {
            throw new InsufficientStockException(product.getProductId(), quantity, product.getStock());
        }
    }

    private MemberCoupon redeemCoupon(Member member, Coupon coupon, BigDecimal subtotal, CouponApplicableType lineType) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        if (!CouponStatus.ACTIVE.equals(coupon.getStatus())
                || now.toLocalDate().isBefore(coupon.getStartDate())
                || now.toLocalDate().isAfter(coupon.getEndDate())) {
            throw new BusinessRuleException("優惠券已失效或不在使用期限內");
        }
        if (!CouponApplicableType.ALL.equals(coupon.getApplicableType()) && !lineType.equals(coupon.getApplicableType())) {
            throw new BusinessRuleException("此優惠券不適用於本次購買的品項類型");
        }
        if (subtotal.compareTo(coupon.getMinSpend()) < 0) {
            throw new BusinessRuleException("未達優惠券最低消費金額");
        }
        MemberCoupon memberCoupon = memberCoupons.findByMemberIdAndCouponCouponIdAndStatus(member.getId(), coupon.getCouponId(),
                MemberCouponStatus.UNUSED).orElseThrow(() -> new BusinessRuleException("尚未持有此優惠券或已使用過"));
        memberCoupon.setStatus(MemberCouponStatus.USED);
        memberCoupon.setUsedAt(now);
        return memberCoupon;
    }

    private BigDecimal computeDiscount(Coupon coupon, BigDecimal subtotal) {
        BigDecimal discount = CouponType.PERCENT.equals(coupon.getType())
                ? subtotal.multiply(coupon.getValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : coupon.getValue();
        return discount.min(subtotal);
    }

    private String generateMerchantTradeNo() {
        String timestamp = LocalDateTime.now(TAIPEI).format(TRADE_NO_FORMAT);
        int suffix = RANDOM.nextInt(1000);
        return "GYM" + timestamp + String.format("%03d", suffix);
    }

    private String generateOrderNo() {
        String timestamp = LocalDateTime.now(TAIPEI).format(TRADE_NO_FORMAT);
        int suffix = RANDOM.nextInt(10000);
        return "ORD" + timestamp + String.format("%04d", suffix);
    }

    private String generateRefundNo() {
        String timestamp = LocalDateTime.now(TAIPEI).format(TRADE_NO_FORMAT);
        int suffix = RANDOM.nextInt(10000);
        return "RF" + timestamp + String.format("%04d", suffix);
    }

    private String valueOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private OrderDetailResponse toDetailResponse(Order order) {
        List<OrderItemResponse> items = orderItems.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .sorted(Comparator.comparing(OrderItem::getId))
                .map(item -> new OrderItemResponse(item.getId(),
                        item.isCourseItem() ? null : item.getProduct().getProductId(),
                        item.isCourseItem() ? item.getCourse().getId() : null,
                        item.getProductNameAtBuy(), item.getPriceAtBuy(), item.getQuantity(),
                        item.getPriceAtBuy().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList();
        return new OrderDetailResponse(order.getId(), order.getMerchantTradeNo(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt(), items);
    }

    private record OrderLine(Product product, int quantity) {}

    private record ProductQuantity(Product product, int quantity) {}
}
