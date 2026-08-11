package tw.com.ispan.gym.service;

import tw.com.ispan.gym.dto.order.CancelOrderRequest;
import tw.com.ispan.gym.dto.order.CreateOrderRequest;
import tw.com.ispan.gym.dto.order.DirectCheckoutRequest;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderItemResponse;
import tw.com.ispan.gym.entity.CartItem;
import tw.com.ispan.gym.entity.Coupon;
import tw.com.ispan.gym.entity.Order;
import tw.com.ispan.gym.entity.OrderItem;
import tw.com.ispan.gym.entity.OrderStatusHistory;
import tw.com.ispan.gym.entity.Product;
import tw.com.ispan.gym.entity.User;
import tw.com.ispan.gym.entity.MemberCoupon;
import tw.com.ispan.gym.enums.CouponApplicableType;
import tw.com.ispan.gym.enums.CouponStatus;
import tw.com.ispan.gym.enums.CouponType;
import tw.com.ispan.gym.enums.ProductStatus;
import tw.com.ispan.gym.enums.ProductType;
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
import tw.com.ispan.gym.repository.ProductRepository;
import tw.com.ispan.gym.repository.UserCouponRepository;
import tw.com.ispan.gym.repository.UserRepository;

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
    private final UserRepository users;
    private final CouponRepository coupons;
    private final UserCouponRepository userCoupons;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderStatusHistoryRepository orderStatusHistories;

    public OrderService(CartItemRepository cartItems, ProductRepository products, UserRepository users,
            CouponRepository coupons, UserCouponRepository userCoupons, OrderRepository orders,
            OrderItemRepository orderItems, OrderStatusHistoryRepository orderStatusHistories) {
        this.cartItems = cartItems;
        this.products = products;
        this.users = users;
        this.coupons = coupons;
        this.userCoupons = userCoupons;
        this.orders = orders;
        this.orderItems = orderItems;
        this.orderStatusHistories = orderStatusHistories;
    }

    @Transactional
    public OrderDetailResponse createOrderFromCart(Integer userId, CreateOrderRequest request) {
        List<CartItem> items = cartItems.findAllByUserIdOrderByCreatedAtAsc(userId);
        if (items.isEmpty()) {
            throw new BusinessRuleException("購物車是空的，無法建立訂單");
        }

        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        List<ProductQuantity> lines = items.stream()
                .map(ci -> new ProductQuantity(ci.getProduct(), ci.getQuantity()))
                .toList();
        String couponCode = request == null ? null : request.couponCode();
        String receiverName = request == null ? null : request.receiverName();
        String receiverPhone = request == null ? null : request.receiverPhone();
        String receiverAddress = request == null ? null : request.receiverAddress();

        Order order = createOrder(user, lines, couponCode, receiverName, receiverPhone, receiverAddress,
                CouponApplicableType.PRODUCT);
        cartItems.deleteAllByUserId(userId);
        return toDetailResponse(order);
    }

    /**
     * 課程略過購物車，直接以 productId + quantity 結帳。會員訂閱的直接結帳流程待資料模型確定後再補上。
     */
    @Transactional
    public OrderDetailResponse createDirectOrder(Integer userId, DirectCheckoutRequest request) {
        if (request.quantity() == null || request.quantity() < 1) {
            throw new InvalidQuantityException();
        }
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        Product product = products.findById(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));
        if (!ProductType.COURSE.equals(product.getType())) {
            throw new DirectCheckoutNotAllowedException(product.getId());
        }

        Order order = createOrder(user, List.of(new ProductQuantity(product, request.quantity())),
                request.couponCode(), null, null, null, CouponApplicableType.COURSE);
        return toDetailResponse(order);
    }

    /**
     * 目前只允許取消未付款的訂單：恢復商品庫存、退回已使用的優惠券。
     * 已付款訂單的取消需搭配退款流程，待金流串接後再開放。
     */
    @Transactional
    public OrderDetailResponse cancelOrder(Integer userId, Long orderId, CancelOrderRequest request) {
        Order order = orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        String fromStatus = order.getStatus().name();
        order.cancel(request == null ? null : request.reason());

        restoreStock(order);
        restoreCoupon(order);

        orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(), order.getUser(),
                "USER", "CANCEL"));
        return toDetailResponse(order);
    }

    private void restoreStock(Order order) {
        List<OrderItem> items = orderItems.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .toList();
        for (OrderItem item : items) {
            Product product = reloadForUpdate(item.getProduct());
            if (ProductType.GOODS.equals(product.getType())) {
                product.setStock(Math.toIntExact(product.getStock() + item.getQuantity()));
            }
        }
    }

    private void restoreCoupon(Order order) {
        MemberCoupon userCoupon = order.getUserCoupon();
        if (userCoupon != null) {
            userCoupon.setStatus(MemberCouponStatus.UNUSED);
            userCoupon.setUsedAt(null);
        }
    }

    private Order createOrder(User user, List<ProductQuantity> productLines, String couponCode,
            String receiverName, String receiverPhone, String receiverAddress, CouponApplicableType lineType) {
        // 依 productId 排序後才逐一鎖定，避免不同結帳請求互相持有對方需要的鎖而死結。
        List<OrderLine> lines = productLines.stream()
                .sorted(Comparator.comparing(pq -> pq.product().getId()))
                .map(pq -> new OrderLine(reloadForUpdate(pq.product()), pq.quantity()))
                .toList();
        for (OrderLine line : lines) {
            validateAvailableAndInStock(line.product(), line.quantity());
        }

        BigDecimal subtotal = lines.stream()
                .map(line -> line.product().getPrice().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        MemberCoupon userCoupon = null;
        BigDecimal discount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            Coupon coupon = coupons.findByCode(couponCode)
                    .orElseThrow(() -> new ResourceNotFoundException("找不到優惠券：" + couponCode));
            userCoupon = redeemCoupon(user, coupon, subtotal, lineType);
            discount = computeDiscount(coupon, subtotal);
        }

        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO);

        String finalReceiverName = valueOr(receiverName, user.getFirstName() + user.getLastName());
        String finalReceiverPhone = valueOr(receiverPhone, user.getPhone());

        Order order = new Order(user, generateMerchantTradeNo(), generateOrderNo(), subtotal, discount, subtotal,
                total, userCoupon, finalReceiverName, finalReceiverPhone, receiverAddress, user.getEmail());
        order = orders.save(order);

        for (OrderLine line : lines) {
            Product product = line.product();
            orderItems.save(new OrderItem(order, product, product.getName(), product.getPrice(), line.quantity()));
            if (ProductType.GOODS.equals(product.getType())) {
                product.setStock(Math.toIntExact(product.getStock() - line.quantity()));
            }
        }

        orderStatusHistories.save(new OrderStatusHistory(order, null, order.getStatus().name(), user, "USER",
                "CHECKOUT"));
        return order;
    }

    private Product reloadForUpdate(Product product) {
        return products.findByIdForUpdate(product.getId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到商品：" + product.getId()));
    }

    private void validateAvailableAndInStock(Product product, long quantity) {
        if (!ProductStatus.ACTIVE.equals(product.getStatus())) {
            throw new ProductUnavailableException(product.getId());
        }
        if (ProductType.GOODS.equals(product.getType()) && quantity > product.getStock()) {
            throw new InsufficientStockException(product.getId(), quantity, product.getStock());
        }
    }

    private MemberCoupon redeemCoupon(User user, Coupon coupon, BigDecimal subtotal, CouponApplicableType lineType) {
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
        MemberCoupon userCoupon = userCoupons.findByUserIdAndCouponIdAndStatus(user.getId(), coupon.getId(),
                MemberCouponStatus.UNUSED).orElseThrow(() -> new BusinessRuleException("尚未持有此優惠券或已使用過"));
        userCoupon.setStatus(MemberCouponStatus.USED);
        userCoupon.setUsedAt(now);
        return userCoupon;
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
                .map(item -> new OrderItemResponse(item.getId(), item.getProduct().getId(),
                        item.getProductNameAtBuy(), item.getPriceAtBuy(), item.getQuantity(),
                        item.getPriceAtBuy().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList();
        return new OrderDetailResponse(order.getId(), order.getMerchantTradeNo(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt(), items);
    }

    private record OrderLine(Product product, long quantity) {}

    private record ProductQuantity(Product product, long quantity) {}
}
