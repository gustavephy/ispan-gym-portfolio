package tw.com.ispan.gym.entity.payment;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Nationalized;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "payment_refund", uniqueConstraints = @UniqueConstraint(
        name = "UX_payment_refund_refund_no", columnNames = "refund_no"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
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
    @Version @Column(nullable = false) private Integer version = 0;
    protected PaymentRefund() {}

    public PaymentRefund(PaymentTransaction payment, String refundNo, BigDecimal amount, String reason) {
        this.payment = payment;
        this.refundNo = refundNo;
        this.amount = amount;
        this.reason = reason;
        this.status = "PENDING";
    }
}
