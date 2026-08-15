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
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, foreignKey = @ForeignKey(name = "FK_orders_user"))
    private Member member;
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;
    @Column(name = "payment_method", length = 20)
    private String paymentMethod;
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
    @JoinColumn(name = "member_coupon_id", foreignKey = @ForeignKey(name = "FK_orders_user_coupon"))
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

    /** 已付款訂單申請退款前的資格檢查；通過後由 OrderService 建立 PaymentRefund（PENDING），訂單狀態維持 PAID 直到後台實際完成退款 */
    public void assertRefundable() {
        if (status != OrderStatus.PAID) {
            throw new BusinessRuleException("只有已付款的訂單可以申請退款");
        }
    }

    /** 後台完成退款：PAID → REFUNDED */
    public void markRefunded() {
        if (status != OrderStatus.PAID) {
            throw new BusinessRuleException("只有已付款的訂單可以標記為已退款");
        }
        this.status = OrderStatus.REFUNDED;
    }

    public Long getId() {
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
