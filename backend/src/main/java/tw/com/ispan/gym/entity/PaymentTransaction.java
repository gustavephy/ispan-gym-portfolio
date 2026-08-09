package tw.com.ispan.gym.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "payment_transactions")
@EntityListeners(AuditingEntityListener.class)
public class PaymentTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id") private Order order;
    @Column(nullable = false, length = 20) private String provider;
    @Column(name = "merchant_trade_no", nullable = false, length = 20) private String merchantTradeNo;
    @Column(name = "trade_no", length = 20) private String tradeNo;
    @Column(name = "payment_method", length = 30) private String paymentMethod;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "rtn_code") private Integer rtnCode;
    @Nationalized @Column(name = "rtn_msg", length = 500) private String rtnMsg;
    @Column(name = "payment_type", length = 50) private String paymentType;
    @Column(name = "charge_fee", precision = 10, scale = 2) private BigDecimal chargeFee;
    @Column(name = "simulate_paid", nullable = false) private Boolean simulatePaid;
    @Nationalized @Lob @Column(name = "request_payload") private String requestPayload;
    @Nationalized @Lob @Column(name = "response_payload") private String responsePayload;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "paid_at") private LocalDateTime paidAt;
    @Version @Column(nullable = false) private Integer version;
    protected PaymentTransaction() {}
}
