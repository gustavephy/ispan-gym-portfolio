package tw.com.ispan.gym.common.idempotency;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import tw.com.ispan.gym.entity.member.Member;

@Entity
@Table(name = "idempotency_request", uniqueConstraints = @UniqueConstraint(
        name = "UX_idempotency_member_operation_key",
        columnNames = { "member_id", "operation_name", "idempotency_key" }))
@EntityListeners(AuditingEntityListener.class)
public class IdempotencyRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "member_id") private Member member;
    @Column(name = "operation_name", nullable = false, length = 50) private String operationName;
    @Column(name = "idempotency_key", nullable = false, length = 100) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "http_status_code") private Short httpStatusCode;
    @Nationalized @Lob @Column(name = "response_body") private String responseBody;
    @Column(name = "resource_id") private Long resourceId;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    protected IdempotencyRequest() {}

    public IdempotencyRequest(Member member, String operationName, String idempotencyKey, String requestHash,
            LocalDateTime expiresAt) {
        this.member = member;
        this.operationName = operationName;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = "PENDING";
        this.expiresAt = expiresAt;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
