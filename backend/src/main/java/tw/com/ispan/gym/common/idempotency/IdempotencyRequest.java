package tw.com.ispan.gym.common.idempotency;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;
import tw.com.ispan.gym.user.entity.User;

@Entity
@Table(name = "idempotency_requests")
public class IdempotencyRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    @Column(name = "operation_name", nullable = false, length = 50) private String operationName;
    @Column(name = "idempotency_key", nullable = false, length = 100) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "http_status_code") private Short httpStatusCode;
    @Nationalized @Lob @Column(name = "response_body") private String responseBody;
    @Column(name = "resource_id") private Long resourceId;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    protected IdempotencyRequest() {}
}
