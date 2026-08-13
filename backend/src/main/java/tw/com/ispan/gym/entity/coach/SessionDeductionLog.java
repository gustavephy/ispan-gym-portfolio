package tw.com.ispan.gym.entity.coach;

import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.coach.CoachBooking;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 銷課紀錄日誌（session_deduction_logs）
 *
 * 記錄每一次堂數異動事件，用於：
 * - 學員查詢自己的上課/取消歷史
 * - 管理員審計銷課爭議
 * - 報表統計
 *
 * eventType 值域：
 * BOOK → 訂課（remaining-1, reserved+1）
 * CHECKIN → 核銷上課（reserved-1, used+1）
 * CANCEL_REFUND → 準時取消≥72h（reserved-1, remaining+1），退還
 * CANCEL_CONSUME→ 遲取消/缺席（reserved-1, used+1），不退還
 * CREDIT → 購課後發放額度（remaining+N）
 */
@Entity
@Table(name = "session_deduction_logs")
@Data
@EntityListeners(AuditingEntityListener.class)
public class SessionDeductionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** 對應的會員課程包 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quota_id", nullable = false)
    private MemberCourseQuota quota;

    /** 對應的學員 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /** 觸發此異動的私教預約（取消/核銷時有值，購課時為 null） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coach_booking_id", nullable = true)
    private CoachBooking coachBooking;

    /** 事件類型 */
    @Column(nullable = false, length = 30)
    private String eventType;

    /** 異動量（+N 表示增加，-N 表示減少，以 remaining 為基準） */
    @Column(nullable = false)
    private Integer delta;

    /** 異動前 remaining */
    @Column(nullable = false)
    private Integer beforeRemaining;

    /** 異動後 remaining */
    @Column(nullable = false)
    private Integer afterRemaining;

    /** 異動前 reserved */
    @Column(nullable = false)
    private Integer beforeReserved;

    /** 異動後 reserved */
    @Column(nullable = false)
    private Integer afterReserved;

    /** 異動前 used */
    @Column(nullable = false)
    private Integer beforeUsed;

    /** 異動後 used */
    @Column(nullable = false)
    private Integer afterUsed;

    /** 備註（如：「訂單 #123 購課發放」、「取消原因：臨時有事」） */
    @Column(length = 200, columnDefinition = "NVARCHAR(200)")
    private String remark;

    /** 執行核銷的教練；會員自助訂課／取消與系統發放額度時為 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_trainer_id", nullable = true)
    private Trainer operatorTrainer;

    @CreatedDate
    @Column(updatable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;
}
