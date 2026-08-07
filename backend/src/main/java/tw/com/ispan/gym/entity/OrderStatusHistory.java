package tw.com.ispan.gym.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id") private Order order;
    @Column(name = "from_status", length = 20) private String fromStatus;
    @Column(name = "to_status", nullable = false, length = 20) private String toStatus;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "changed_by_id") private User changedBy;
    @Column(name = "change_source", nullable = false, length = 20) private String changeSource;
    @Nationalized @Column(length = 500) private String note;
    @Column(name = "changed_at", nullable = false, insertable = false, updatable = false) private LocalDateTime changedAt;
    protected OrderStatusHistory() {}
}
