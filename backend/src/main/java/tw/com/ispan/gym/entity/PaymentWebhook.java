package tw.com.ispan.gym.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "payment_webhooks")
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
    @Column(name = "received_at", nullable = false, insertable = false, updatable = false) private LocalDateTime receivedAt;
    @Column(name = "processed_at") private LocalDateTime processedAt;
    protected PaymentWebhook() {}
}
