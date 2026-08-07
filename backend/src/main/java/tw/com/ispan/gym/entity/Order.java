package tw.com.ispan.gym.entity;

import tw.com.ispan.gym.common.persistence.TaipeiTimestampEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "orders", uniqueConstraints = @UniqueConstraint(name = "UQ_orders_mtn", columnNames = "merchant_trade_no"))
public class Order extends TaipeiTimestampEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "FK_orders_user"))
    private User user;
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;
    @Column(name = "payment_method", length = 20)
    private String paymentMethod;
    @Column(name = "merchant_trade_no", nullable = false, length = 20)
    private String merchantTradeNo;
    @Column(name = "trade_no", length = 20)
    private String tradeNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
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
    /** 本筆訂單消耗掉的那一張已領取優惠券（券種可由 userCoupon.coupon 取得） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_coupon_id", foreignKey = @ForeignKey(name = "FK_orders_user_coupon"))
    private UserCoupon userCoupon;
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
    private Integer version;

    protected Order() {}
    public Long getId() { return id; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getMerchantTradeNo() { return merchantTradeNo; }
    public OrderStatus getStatus() { return status; }
    public UserCoupon getUserCoupon() { return userCoupon; }
    public String getOrderNo() { return orderNo; }
    public String getPaymentStatus() { return paymentStatus; }
    public BigDecimal getSubtotalAmount() { return subtotalAmount; }
}

