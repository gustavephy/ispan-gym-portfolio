package tw.com.ispan.gym.service.order;



import tw.com.ispan.gym.dto.order.CancelOrderRequest;
import tw.com.ispan.gym.dto.order.CreateOrderRequest;
import tw.com.ispan.gym.dto.order.DirectCheckoutRequest;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderItemResponse;
import tw.com.ispan.gym.entity.cart.CartItem;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.entity.order.OrderStatusHistory;
import tw.com.ispan.gym.entity.course.Course;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.coupon.MemberCoupon;
import tw.com.ispan.gym.enums.CouponApplicableType;
import tw.com.ispan.gym.enums.CouponStatus;
import tw.com.ispan.gym.enums.CouponType;
import tw.com.ispan.gym.enums.ProductStatus;
import tw.com.ispan.gym.enums.CourseStatus;
import tw.com.ispan.gym.enums.MemberCouponStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.DirectCheckoutNotAllowedException;
import tw.com.ispan.gym.exception.InsufficientStockException;
import tw.com.ispan.gym.exception.InvalidQuantityException;
import tw.com.ispan.gym.exception.ProductNotFoundException;
import tw.com.ispan.gym.exception.ProductUnavailableException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.CartItemRepository;
import tw.com.ispan.gym.repository.CouponRepository;
import tw.com.ispan.gym.repository.OrderItemRepository;
import tw.com.ispan.gym.repository.OrderRepository;
import tw.com.ispan.gym.repository.OrderStatusHistoryRepository;
import tw.com.ispan.gym.repository.CourseRepository;
import tw.com.ispan.gym.repository.ProductRepository;
import tw.com.ispan.gym.repository.MemberCouponRepository;
import tw.com.ispan.gym.repository.MemberRepository;

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
 * 購物車結帳（{@link #createOrderFromCart}）用於一般商品；課程則透過
 * {@link #createDirectOrder} 略過購物車直接結帳。會員訂閱的直接結帳流程待資料模型確定後再補上。
 * {@link #cancelOrder} 目前僅支援取消未付款訂單，退款流程待金流串接後再補上。
 */
@Service
@Transactional(readOnly = true)
public class OrderService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TRADE_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final CourseRepository courses;
    private final MemberRepository users;
    private final CouponRepository coupons;
    private final MemberCouponRepository memberCoupons;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderStatusHistoryRepository orderStatusHistories;

    public OrderService(CartItemRepository cartItems, ProductRepository products, CourseRepository courses,
            MemberRepository users, CouponRepository coupons, MemberCouponRepository memberCoupons,
            OrderRepository orders, OrderItemRepository orderItems,
            OrderStatusHistoryRepository orderStatusHistories) {
        this.cartItems = cartItems;
        this.products = products;
        this.courses = courses;
        this.users = users;
        this.coupons = coupons;
        this.memberCoupons = memberCoupons;
        this.orders = orders;
        this.orderItems = orderItems;
        this.orderStatusHistories = orderStatusHistories;
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
     * 課程略過購物車，直接以 courseId + quantity 結帳。會員訂閱的直接結帳流程待資料模型確定後再補上。
     */
    @Transactional
    public OrderDetailResponse createDirectOrder(Integer userId, DirectCheckoutRequest request) {
        if (request.quantity() == null || request.quantity() < 1) {
            throw new InvalidQuantityException();
        }
        Member user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        Course course = courses.findById(request.courseId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到課程：" + request.courseId()));
        if (!CourseStatus.ACTIVE.equals(course.getStatus())) {
            throw new DirectCheckoutNotAllowedException(course.getCourseId());
        }

        Order order = createCourseOrder(user, course, request.quantity(), request.couponCode());
        return toDetailResponse(order);
    }

    /**
     * 課程訂單：不涉庫存，明細以 course_id 記錄。
     * 履約（發放堂數額度）待 MemberCourseQuota 服務接上後補。
     */
    private Order createCourseOrder(Member user, Course course, long quantity, String couponCode) {
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
     * 目前只允許取消未付款的訂單：恢復商品庫存、退回已使用的優惠券。
     * 已付款訂單的取消需搭配退款流程，待金流串接後再開放。
     */
    @Transactional
    public OrderDetailResponse cancelOrder(Integer userId, Long orderId, CancelOrderRequest request) {
        Order order = orders.findByIdAndMemberId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        String fromStatus = order.getStatus().name();
        order.cancel(request == null ? null : request.reason());

        restoreStock(order);
        restoreCoupon(order);

        orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(), order.getMember(),
                "USER", "CANCEL"));
        return toDetailResponse(order);
    }

    /** 只有商品明細需要還原庫存；課程明細（course_id 非空）不涉庫存 */
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

    private String valueOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private OrderDetailResponse toDetailResponse(Order order) {
        List<OrderItemResponse> items = orderItems.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .sorted(Comparator.comparing(OrderItem::getId))
                .map(item -> new OrderItemResponse(item.getId(),
                        item.isCourseItem() ? null : item.getProduct().getProductId(),
                        item.isCourseItem() ? item.getCourse().getCourseId() : null,
                        item.getProductNameAtBuy(), item.getPriceAtBuy(), item.getQuantity(),
                        item.getPriceAtBuy().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList();
        return new OrderDetailResponse(order.getId(), order.getMerchantTradeNo(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt(), items);
    }

    private record OrderLine(Product product, long quantity) {}

    private record ProductQuantity(Product product, long quantity) {}
}
