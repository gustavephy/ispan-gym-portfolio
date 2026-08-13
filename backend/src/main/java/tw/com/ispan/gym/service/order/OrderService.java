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
import tw.com.ispan.gym.entity.coupon.MemberCoupon;
import tw.com.ispan.gym.entity.subscription.MemberSubscription;
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.coupon.CouponType;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.enums.product.ProductStatus;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.enums.coupon.MemberCouponStatus;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.DirectCheckoutNotAllowedException;
import tw.com.ispan.gym.exception.InsufficientStockException;
import tw.com.ispan.gym.exception.InvalidQuantityException;
import tw.com.ispan.gym.exception.ProductNotFoundException;
import tw.com.ispan.gym.exception.ProductUnavailableException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
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
import tw.com.ispan.gym.repository.coupon.MemberCouponRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.subscription.SubscriptionPlanRepository;
import tw.com.ispan.gym.repository.subscription.SubscriptionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 購物車結帳（{@link #createOrderFromCart}）用於一般商品；課程與會員訂閱則分別透過
 * {@link #createDirectOrder}／{@link #createSubscriptionOrder} 略過購物車直接結帳。
 * {@link #cancelOrder} 依訂單狀態分流：未付款直接取消並還原庫存/優惠券；已付款則建立
 * 待處理退款（{@link PaymentRefund}），實際退款與訂單狀態轉移由後台呼叫
 * {@link #completeRefund} 完成。課程/訂閱的履約（發放堂數額度、啟用訂閱）
 * 由 {@link #fulfillPaidOrder} 在金流付款成功後觸發，見 EcpayService。訂閱不允許提前續約，
 * 名下有未到期的有效訂閱時 {@link #createSubscriptionOrder} 會直接擋下結帳請求。
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
    private final MemberRepository users;
    private final CouponRepository coupons;
    private final MemberCouponRepository memberCoupons;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderStatusHistoryRepository orderStatusHistories;
    private final SubscriptionPlanRepository subscriptionPlans;
    private final SubscriptionRepository subscriptions;
    private final MemberCourseQuotaRepository courseQuotas;
    private final PaymentTransactionRepository paymentTransactions;
    private final PaymentRefundRepository paymentRefunds;

    public OrderService(CartItemRepository cartItems, ProductRepository products, PrivateCourseRepository courses,
            MemberRepository users, CouponRepository coupons, MemberCouponRepository memberCoupons,
            OrderRepository orders, OrderItemRepository orderItems,
            OrderStatusHistoryRepository orderStatusHistories, SubscriptionPlanRepository subscriptionPlans,
            SubscriptionRepository subscriptions, MemberCourseQuotaRepository courseQuotas,
            PaymentTransactionRepository paymentTransactions, PaymentRefundRepository paymentRefunds) {
        this.cartItems = cartItems;
        this.products = products;
        this.courses = courses;
        this.users = users;
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
    }

    @Transactional
    public OrderDetailResponse createOrderFromCart(Integer userId, CreateOrderRequest request) {
        List<CartItem> items = cartItems.findAllByMemberIdOrderByCreatedAtAsc(userId);
        if (items.isEmpty()) {
            throw new BusinessRuleException("購物車是空的，無法建立訂單");
        }

        Member user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        List<ProductQuantity> lines = items.stream()
                .map(ci -> new ProductQuantity(ci.getProduct(), ci.getQuantity()))
                .toList();
        String couponCode = request == null ? null : request.couponCode();
        String receiverName = request == null ? null : request.receiverName();
        String receiverPhone = request == null ? null : request.receiverPhone();
        String receiverAddress = request == null ? null : request.receiverAddress();

        Order order = createOrder(user, lines, couponCode, receiverName, receiverPhone, receiverAddress,
                CouponApplicableType.PRODUCT);
        cartItems.deleteAllByMemberId(userId);
        return toDetailResponse(order);
    }

    /**
     * 私教課略過購物車，直接以 privateCourseId + quantity 結帳（團課憑訂閱參加，不走購買）。
     * 會員訂閱請改用 {@link #createSubscriptionOrder}。
     */
    @Transactional
    public OrderDetailResponse createDirectOrder(Integer userId, DirectCheckoutRequest request) {
        if (request.quantity() == null || request.quantity() < 1) {
            throw new InvalidQuantityException();
        }
        Member user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        PrivateCourse course = courses.findById(request.privateCourseId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到私教課：" + request.privateCourseId()));
        if (!CourseStatus.ACTIVE.equals(course.getStatus())) {
            throw new DirectCheckoutNotAllowedException(course.getId());
        }

        Order order = createCourseOrder(user, course, request.quantity(), request.couponCode());
        return toDetailResponse(order);
    }

    /**
     * 私教課訂單：不涉庫存，明細以 private_course_id 記錄。
     * 履約（發放堂數額度）待付款成功後由 {@link #fulfillPaidOrder} 執行。
     */
    private Order createCourseOrder(Member user, PrivateCourse course, long quantity, String couponCode) {
        BigDecimal subtotal = course.getPrice().multiply(BigDecimal.valueOf(quantity));

        MemberCoupon memberCoupon = null;
        BigDecimal discount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            Coupon coupon = coupons.findByCode(couponCode)
                    .orElseThrow(() -> new ResourceNotFoundException("找不到優惠券：" + couponCode));
            memberCoupon = redeemCoupon(user, coupon, subtotal, CouponApplicableType.COURSE);
            discount = computeDiscount(coupon, subtotal);
        }

        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO);
        Order order = orders.save(new Order(user, generateMerchantTradeNo(), generateOrderNo(), subtotal, discount,
                subtotal, total, memberCoupon, user.getFirstName() + user.getLastName(), user.getPhone(), null,
                user.getAccount().getEmail()));

        orderItems.save(new OrderItem(order, course, course.getName(), course.getPrice(), quantity));
        orderStatusHistories.save(new OrderStatusHistory(order, null, order.getStatus().name(), user, "USER",
                "CHECKOUT"));
        return order;
    }

    /**
     * 會員訂閱略過購物車，直接以 subscriptionPlanId 結帳。訂閱無數量概念，一次買一個方案週期。
     * 不允許提前續約：會員名下還有未到期的有效訂閱時直接擋在結帳這一步，等到期後才能再買下一期，
     * 避免付款成功後 {@link #fulfillPaidOrder} 還要處理「新舊訂閱重疊」的狀態。
     */
    @Transactional
    public OrderDetailResponse createSubscriptionOrder(Integer userId, SubscriptionCheckoutRequest request) {
        Member user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        SubscriptionPlan plan = subscriptionPlans.findById(request.subscriptionPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂閱方案：" + request.subscriptionPlanId()));
        if (!"ACTIVE".equals(plan.getStatus())) {
            throw new BusinessRuleException("此訂閱方案目前無法購買");
        }
        if (subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                userId, LocalDateTime.now(TAIPEI), SubscriptionStatus.ACTIVE).isPresent()) {
            throw new BusinessRuleException("尚有未到期的訂閱，到期後才能購買下一期");
        }

        Order order = createSubscriptionOrderInternal(user, plan, request.couponCode());
        return toDetailResponse(order);
    }

    private Order createSubscriptionOrderInternal(Member user, SubscriptionPlan plan, String couponCode) {
        BigDecimal subtotal = plan.getPrice();

        MemberCoupon memberCoupon = null;
        BigDecimal discount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            Coupon coupon = coupons.findByCode(couponCode)
                    .orElseThrow(() -> new ResourceNotFoundException("找不到優惠券：" + couponCode));
            memberCoupon = redeemCoupon(user, coupon, subtotal, CouponApplicableType.SUBSCRIPTION);
            discount = computeDiscount(coupon, subtotal);
        }

        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO);
        Order order = new Order(user, generateMerchantTradeNo(), generateOrderNo(), subtotal, discount,
                subtotal, total, memberCoupon, user.getFirstName() + user.getLastName(), user.getPhone(), null,
                user.getAccount().getEmail());
        order.setSubscriptionPlan(plan);
        order = orders.save(order);

        orderStatusHistories.save(new OrderStatusHistory(order, null, order.getStatus().name(), user, "USER",
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
    public OrderDetailResponse cancelOrder(Integer userId, Long orderId, CancelOrderRequest request) {
        Order order = orders.findByIdAndMemberId(orderId, userId)
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
        paymentRefunds.save(new PaymentRefund(payment, generateRefundNo(), order.getTotalAmount(), reason));
    }

    /** 後台人工執行退款完成：退款狀態轉 COMPLETED、還原庫存/優惠券、訂單轉 REFUNDED */
    @Transactional
    public void completeRefund(Long refundId) {
        PaymentRefund refund = paymentRefunds.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到退款紀錄：" + refundId));
        if (!"PENDING".equals(refund.getStatus())) {
            throw new BusinessRuleException("此退款已處理過");
        }
        Order order = refund.getPayment().getOrder();
        String fromStatus = order.getStatus().name();

        refund.setStatus("COMPLETED");
        refund.setCompletedAt(LocalDateTime.now(TAIPEI));

        restoreStock(order);
        restoreCoupon(order);
        order.markRefunded();

        orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(), order.getMember(),
                "ADMIN", "REFUND_COMPLETE"));
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

    private Order createOrder(Member user, List<ProductQuantity> productLines, String couponCode,
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
            memberCoupon = redeemCoupon(user, coupon, subtotal, lineType);
            discount = computeDiscount(coupon, subtotal);
        }

        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO);

        String finalReceiverName = valueOr(receiverName, user.getFirstName() + user.getLastName());
        String finalReceiverPhone = valueOr(receiverPhone, user.getPhone());

        Order order = new Order(user, generateMerchantTradeNo(), generateOrderNo(), subtotal, discount, subtotal,
                total, memberCoupon, finalReceiverName, finalReceiverPhone, receiverAddress, user.getAccount().getEmail());
        order = orders.save(order);

        for (OrderLine line : lines) {
            Product product = line.product();
            orderItems.save(new OrderItem(order, product, product.getName(), product.getPrice(), line.quantity()));
            product.setStock(Math.toIntExact(product.getStock() - line.quantity()));
        }

        orderStatusHistories.save(new OrderStatusHistory(order, null, order.getStatus().name(), user, "USER",
                "CHECKOUT"));
        return order;
    }

    private Product reloadForUpdate(Product product) {
        return products.findByIdForUpdate(product.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到商品：" + product.getProductId()));
    }

    private void validateAvailableAndInStock(Product product, long quantity) {
        if (!ProductStatus.ACTIVE.equals(product.getStatus())) {
            throw new ProductUnavailableException(product.getProductId());
        }
        if (quantity > product.getStock()) {
            throw new InsufficientStockException(product.getProductId(), quantity, product.getStock());
        }
    }

    private MemberCoupon redeemCoupon(Member user, Coupon coupon, BigDecimal subtotal, CouponApplicableType lineType) {
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
        MemberCoupon memberCoupon = memberCoupons.findByMemberIdAndCouponCouponIdAndStatus(user.getId(), coupon.getCouponId(),
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

    private record OrderLine(Product product, long quantity) {}

    private record ProductQuantity(Product product, long quantity) {}
}
