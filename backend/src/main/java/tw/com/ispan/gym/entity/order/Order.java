package tw.com.ispan.gym.entity.order;

import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;

import tw.com.ispan.gym.enums.order.OrderStatus;

import tw.com.ispan.gym.common.persistence.TaipeiTimestampEntity;
import tw.com.ispan.gym.exception.BusinessRuleException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "orders", uniqueConstraints = {
        @UniqueConstraint(name = "UQ_orders_mtn", columnNames = "merchant_trade_no"),
        @UniqueConstraint(name = "UX_orders_order_no", columnNames = "order_no") })
public class Order extends TaipeiTimestampEntity {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, foreignKey = @ForeignKey(name = "FK_orders_member"))
    private Member member;
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;
    @Column(name = "payment_method", length = 20)
    private String paymentMethod;
    /**
     * 結帳當下使用者選擇的付款方式（Credit／ATM／CVS），跟 {@link #paymentMethod} 分開存放——
     * 後者要留給「金流分布報表」（見 OrderRepository#aggregateByPaymentMethod）只統計真正走過
     * 付款流程的訂單；若拿「當初選了什麼」去覆蓋，會讓還沒真正付款的訂單也被算進報表裡失真。
     */
    @Column(name = "intended_payment_method", length = 20)
    private String intendedPaymentMethod;
    @Column(name = "merchant_trade_no", nullable = false, length = 20)
    private String merchantTradeNo;
    @Column(name = "trade_no", length = 20)
    private String tradeNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OrderStatus status;
    @Column(name = "paid_at")
    private LocalDateTime paidAt;
    @Nationalized
    @Column(name = "receiver_name", length = 50)
    private String receiverName;
    @Column(name = "receiver_phone", length = 20)
    private String receiverPhone;
    @Nationalized
    @Column(name = "receiver_address", length = 200)
    private String receiverAddress;
    /** 本筆訂單消耗掉的那一張已領取優惠券（券種可由 memberCoupon.coupon 取得） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_coupon_id", foreignKey = @ForeignKey(name = "FK_orders_member_coupon"))
    private MemberCoupon memberCoupon;
    /**
     * 有值代表這是一筆訂閱方案購買。訂閱不像商品/課程走 order_items 多型
     * （product_id ⊕ course_id 的 CK_oi_exactly_one_ref 互斥），直接在 Order 上標記，
     * 履約（建立訂閱）待付款成功後才執行，見 OrderService#fulfillPaidOrder。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_plan_id", foreignKey = @ForeignKey(name = "FK_orders_subscription_plan"))
    private SubscriptionPlan subscriptionPlan;
    @Column(name = "original_amount", precision = 10, scale = 2)
    private BigDecimal originalAmount;
    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount;
    @Column(name = "order_no", nullable = false, length = 30)
    private String orderNo;
    @Column(name = "payment_status", nullable = false, length = 20)
    private String paymentStatus;
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
    @Column(name = "subtotal_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotalAmount;
    @Column(name = "receiver_email", length = 100)
    private String receiverEmail;
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
    @Nationalized
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    protected Order() {
    }

    public Order(Member member, String merchantTradeNo, String orderNo, BigDecimal originalAmount,
            BigDecimal discountAmount, BigDecimal subtotalAmount, BigDecimal totalAmount,
            MemberCoupon memberCoupon, String receiverName, String receiverPhone, String receiverAddress,
            String receiverEmail) {
        this.member = member;
        this.merchantTradeNo = merchantTradeNo;
        this.orderNo = orderNo;
        this.originalAmount = originalAmount;
        this.discountAmount = discountAmount;
        this.subtotalAmount = subtotalAmount;
        this.totalAmount = totalAmount;
        this.memberCoupon = memberCoupon;
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.receiverAddress = receiverAddress;
        this.receiverEmail = receiverEmail;
        this.status = OrderStatus.UNPAID;
        this.paymentStatus = OrderStatus.UNPAID.name();
        this.currency = "TWD";
    }

    /**
     * 目前只允許取消未付款的訂單；已付款後的取消需搭配退款流程，待金流串接後再開放。
     * payment_status 不隨之變動——這筆訂單從未發生過金流事件，維持 UNPAID 即可；
     * CANCELLED 不是資料庫 CK_orders_payment_status 允許的值。
     */
    public void cancel(String reason) {
        if (status != OrderStatus.UNPAID) {
            throw new BusinessRuleException("只有未付款的訂單可以取消");
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now(TAIPEI);
        this.cancelReason = reason;
    }

    /**
     * 結帳當下記錄使用者選擇的付款方式，只在 UNPAID 時才有意義（付款完成後應以
     * {@link #paymentMethod} 上綠界回傳的實際值為準，不該再被這裡覆蓋）。
     */
    public void recordIntendedPaymentMethod(String method) {
        if (status == OrderStatus.UNPAID) {
            this.intendedPaymentMethod = method;
        }
    }

    /** ECPay 通知付款成功：UNPAID → PAID。paymentStatus 維持既有值不動，理由同 {@link #cancel} */
    public void markPaid(String paymentMethod, String tradeNo) {
        if (status != OrderStatus.UNPAID) {
            throw new BusinessRuleException("只有未付款的訂單可以標記為已付款");
        }
        this.status = OrderStatus.PAID;
        this.paymentMethod = paymentMethod;
        this.tradeNo = tradeNo;
        this.paidAt = LocalDateTime.now(TAIPEI);
    }

    /** ECPay 通知付款失敗：UNPAID → FAILED */
    public void markFailed() {
        if (status != OrderStatus.UNPAID) {
            throw new BusinessRuleException("只有未付款的訂單可以標記為付款失敗");
        }
        this.status = OrderStatus.FAILED;
    }

    /**
     * 申請退款（含退貨）前的資格檢查；已出貨/已完成的訂單視同已付款，一樣可以申請——
     * 商品出貨後客人才要求退貨是正常情境，不是只有出貨前才能反悔。
     * 通過後由 OrderService 建立 PaymentRefund（PENDING），訂單狀態維持原樣直到後台實際完成退款。
     */
    public void assertRefundable() {
        if (status != OrderStatus.PAID && status != OrderStatus.SHIPPED && status != OrderStatus.COMPLETED) {
            throw new BusinessRuleException("只有已付款、已出貨或已完成的訂單可以申請退款");
        }
    }

    /** 後台完成退款：PAID／SHIPPED／COMPLETED → REFUNDED */
    public void markRefunded() {
        if (status != OrderStatus.PAID && status != OrderStatus.SHIPPED && status != OrderStatus.COMPLETED) {
            throw new BusinessRuleException("只有已付款、已出貨或已完成的訂單可以標記為已退款");
        }
        this.status = OrderStatus.REFUNDED;
    }

    /** 後台標記已出貨：PAID → SHIPPED。僅適用於含實體商品的訂單，課程/訂閱訂單無需這一步 */
    public void markShipped() {
        if (status != OrderStatus.PAID) {
            throw new BusinessRuleException("只有已付款的訂單可以標記為已出貨");
        }
        this.status = OrderStatus.SHIPPED;
    }

    /** 後台標記訂單完成：SHIPPED → COMPLETED；課程/訂閱訂單無出貨步驟，允許直接從 PAID 完成 */
    /**
     * 標記為已完成。
     *
     * <p>含實體商品的訂單必須先出貨（PAID → SHIPPED → COMPLETED）；私教課與訂閱方案
     * 沒有實體要寄，付款後可直接完成（PAID → COMPLETED），不必經過出貨。
     *
     * <p>{@code hasPhysicalProduct} 由呼叫端查明細後傳入——Order 沒有持有 orderItems
     * 關聯（明細以 order_id 反向關聯，見 OrderItem），實體本身查不到自己的品項組成。
     */
    public void markCompleted(boolean hasPhysicalProduct) {
        if (status != OrderStatus.SHIPPED && status != OrderStatus.PAID) {
            throw new BusinessRuleException("只有已出貨或已付款的訂單可以標記為已完成");
        }
        if (hasPhysicalProduct && status != OrderStatus.SHIPPED) {
            throw new BusinessRuleException("此訂單含實體商品，請先標記為已出貨再完成");
        }
        this.status = OrderStatus.COMPLETED;
    }

    public Integer getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public String getIntendedPaymentMethod() {
        return intendedPaymentMethod;
    }

    public String getMerchantTradeNo() {
        return merchantTradeNo;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public String getReceiverEmail() {
        return receiverEmail;
    }

    public MemberCoupon getMemberCoupon() {
        return memberCoupon;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getSubtotalAmount() {
        return subtotalAmount;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Integer getVersion() {
        return version;
    }

    public SubscriptionPlan getSubscriptionPlan() {
        return subscriptionPlan;
    }

    public void setSubscriptionPlan(SubscriptionPlan subscriptionPlan) {
        this.subscriptionPlan = subscriptionPlan;
    }
}
