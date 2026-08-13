package tw.com.ispan.gym.entity.coach;

import tw.com.ispan.gym.entity.trainer.Trainer;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.EqualsAndHashCode;
import tw.com.ispan.gym.common.persistence.TaipeiTimestampEntity;
import tw.com.ispan.gym.enums.coach.SlotStatus;

@Entity
@Table(name = "coach_slot")
@Data
@EqualsAndHashCode(callSuper = false)
public class CoachSlot extends TaipeiTimestampEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /**
     * 這個時段屬於哪位教練。
     * 用 LAZY 載入：查詢時段列表通常只需要比對 trainer.id（本人／非本人），
     * 不需要每次都把整個 User 撈出來。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trainer_id", nullable = false)
    private Trainer trainer;

    @Column(name = "start_at", nullable = false, columnDefinition = "datetime2")
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false, columnDefinition = "datetime2")
    private LocalDateTime endAt;

    /**
     * 時段狀態，預設 AVAILABLE（新建立的時段一定是可預約狀態，
     * BOOKED 由會員預約時才會轉換，這裡先不涉及）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SlotStatus status = SlotStatus.AVAILABLE;

    /** 樂觀鎖版本號，由 Hibernate 自動維護，不要手動賦值。 */
    @Version
    private Integer version;
}