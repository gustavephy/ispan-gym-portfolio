package tw.com.ispan.gym.payment.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "payment_refunds")
public class PaymentRefund {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "payment_id") private PaymentTransaction payment;
    @Column(name = "refund_no", nullable = false, length = 30) private String refundNo;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Nationalized @Column(length = 500) private String reason;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "provider_refund_no", length = 50) private String providerRefundNo;
    @Nationalized @Lob @Column(name = "response_payload") private String responsePayload;
    @Column(name = "requested_at", nullable = false, insertable = false, updatable = false) private LocalDateTime requestedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false) private LocalDateTime updatedAt;
    @Version @Column(nullable = false) private Integer version;
    protected PaymentRefund() {}
}
