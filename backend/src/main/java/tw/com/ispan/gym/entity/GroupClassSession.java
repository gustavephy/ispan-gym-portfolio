package tw.com.ispan.gym.entity;
import tw.com.ispan.gym.enums.GroupClassSessionStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;

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
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.Data;

@Entity
@Table(name = "group_class_sessions")
@Data
@EntityListeners(AuditingEntityListener.class)
public class GroupClassSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "room_name", length = 100, columnDefinition = "NVARCHAR(100)")
    private String roomName;

    @Column(name = "start_at", nullable = false, columnDefinition = "datetime2")
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false, columnDefinition = "datetime2")
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private GroupClassSessionStatus status = GroupClassSessionStatus.SCHEDULED;

    /** 本場次可容納人數。建立時由 Course.maxCapacity 帶入，之後可依實際教室調整 */
    @Column(nullable = false)
    private Integer capacity;

    /** 目前狀態為 BOOKED 的報名數。與 @Version 搭配，避免併發報名造成超賣 */
    @Column(name = "booked_count", nullable = false)
    private Integer bookedCount = 0;

    @Version
    private Integer version = 0;

    @CreatedDate
    @Column(updatable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(columnDefinition = "datetime2")
    private LocalDateTime updatedAt;

    // ─── 便利方法 ───────────────────────────────────────────────

    /** 報名：bookedCount+1。額滿或場次已取消則拒絕 */
    public void book() {
        if (status != GroupClassSessionStatus.SCHEDULED)
            throw new BusinessRuleException("此場次已取消，無法報名");
        if (bookedCount >= capacity)
            throw new BusinessRuleException("此場次已額滿");
        this.bookedCount++;
    }

    /** 取消報名：bookedCount-1 */
    public void cancelBooking() {
        if (bookedCount <= 0)
            throw new BusinessRuleException("此場次沒有可取消的報名");
        this.bookedCount--;
    }

    /** 剩餘名額 */
    @Transient
    public int getRemainingSeats() {
        return capacity - bookedCount;
    }
}