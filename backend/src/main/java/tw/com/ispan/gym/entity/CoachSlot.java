package tw.com.ispan.gym.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
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
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 教練可預約時段。
 *
 * 唯一性約束以篩選索引維護，無法用 @UniqueConstraint 表達
 * （已取消的時段應允許在同一時間重新開放）：
 *
 * CREATE UNIQUE INDEX UX_coach_slots_trainer_start
 *     ON coach_slots(trainer_id, start_at)
 *     WHERE status <> 'CANCELLED';
 *
 * 註：此索引只擋「起始時間完全相同」的重複時段，時段部分重疊
 * （09:00-10:00 與 09:30-10:30）仍須由 service 層檢查。
 */
@Entity
@Table(name = "coach_slots")
@Data
@EntityListeners(AuditingEntityListener.class)
public class CoachSlot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trainer_id", nullable = false)
    private User trainer;

    @Column(name = "start_at", nullable = false, columnDefinition = "datetime2")
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false, columnDefinition = "datetime2")
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SlotStatus status = SlotStatus.AVAILABLE;

    @Version
    private Integer version;

    @CreatedDate
    @Column(updatable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(columnDefinition = "datetime2")
    private LocalDateTime updatedAt;
}
