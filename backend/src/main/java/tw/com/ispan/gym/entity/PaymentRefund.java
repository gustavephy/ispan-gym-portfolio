package tw.com.ispan.gym.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "payment_refunds")
@EntityListeners(AuditingEntityListener.class)
public class PaymentRefund {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "payment_id") private PaymentTransaction payment;
    @Column(name = "refund_no", nullable = false, length = 30) private String refundNo;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Nationalized @Column(length = 500) private String reason;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "provider_refund_no", length = 50) private String providerRefundNo;
    @Nationalized @Lob @Column(name = "response_payload") private String responsePayload;
    @CreatedDate @Column(name = "requested_at", nullable = false, updatable = false) private LocalDateTime requestedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Version @Column(nullable = false) private Integer version;
    protected PaymentRefund() {}
}
