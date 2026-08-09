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

@Entity
@Table(name = "coach_bookings")
@Data
@EntityListeners(AuditingEntityListener.class)
public class CoachBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** 被預約的時段（單一資料來源，時間資訊都從這裡查） */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coach_slot_id", nullable = false)
    private CoachSlot coachSlot;

    /** 預約的會員 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private User member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CoachBookingStatus status = CoachBookingStatus.PENDING;

    /** 銷課金鑰，會員產生，教練核銷時輸入比對 */
    @Column(name = "check_in_key", length = 50)
    private String checkInKey;

    @Column(name = "check_in_time", columnDefinition = "datetime2")
    private LocalDateTime checkInTime;

    /** 執行核銷的教練（存 FK，不存姓名字串快照） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "check_in_approver_id")
    private User checkInApprover;

    @CreatedDate
    @Column(updatable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;
}