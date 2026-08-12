package tw.com.ispan.gym.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "order_status_history")
@EntityListeners(AuditingEntityListener.class)
public class OrderStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;
    @Column(name = "from_status", length = 20)
    private String fromStatus;
    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by_id")
    private Member changedBy;
    @Column(name = "change_source", nullable = false, length = 20)
    private String changeSource;
    @Nationalized
    @Column(length = 500)
    private String note;
    @CreatedDate
    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    protected OrderStatusHistory() {
    }

    public OrderStatusHistory(Order order, String fromStatus, String toStatus, Member changedBy, String changeSource,
            String note) {
        this.order = order;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.changeSource = changeSource;
        this.note = note;
    }
}
