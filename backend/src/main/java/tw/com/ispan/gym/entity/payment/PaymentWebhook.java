package tw.com.ispan.gym.entity.payment;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "payment_webhook", uniqueConstraints = @UniqueConstraint(
        name = "UX_payment_webhook_provider_event", columnNames = { "provider", "event_key" }))
@EntityListeners(AuditingEntityListener.class)
public class PaymentWebhook {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 20) private String provider;
    @Column(name = "event_key", nullable = false, length = 64) private String eventKey;
    @Column(name = "merchant_trade_no", length = 20) private String merchantTradeNo;
    @Column(name = "trade_no", length = 20) private String tradeNo;
    @Nationalized @Lob @Column(nullable = false) private String payload;
    @Column(name = "check_mac_valid", nullable = false) private Boolean checkMacValid;
    @Column(name = "processing_status", nullable = false, length = 20) private String processingStatus;
    @Nationalized @Column(name = "error_message", length = 1000) private String errorMessage;
    @CreatedDate @Column(name = "received_at", nullable = false, updatable = false) private LocalDateTime receivedAt;
    @Column(name = "processed_at") private LocalDateTime processedAt;
    protected PaymentWebhook() {}
}
