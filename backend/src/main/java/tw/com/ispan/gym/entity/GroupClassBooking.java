package tw.com.ispan.gym.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 團課報名。
 *
 * 唯一性約束以篩選索引維護，無法用 @UniqueConstraint 表達
 * （直接對 (session_id, member_id) 下 unique 會讓取消後無法重新報名）：
 *
 * CREATE UNIQUE INDEX UX_gcb_session_member
 *     ON group_class_bookings(session_id, member_id)
 *     WHERE status = 'BOOKED';
 */
@Entity
@Table(name = "group_class_bookings")
@Data
@EntityListeners(AuditingEntityListener.class)
public class GroupClassBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private GroupClassSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private User member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupClassBookingStatus status = GroupClassBookingStatus.BOOKED;

    @CreatedDate
    @Column(updatable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;
}