package tw.com.ispan.gym.repository.coach;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.coach.GroupClassBooking;
import tw.com.ispan.gym.enums.coach.GroupClassBookingStatus;

public interface GroupClassBookingRepository extends JpaRepository<GroupClassBooking, Integer> {
    /**
     * 查詢某會員對某場次是否已有效報名（status=BOOKED）。
     * 用來在真正 INSERT 之前先擋一次重複報名，體驗比直接讓資料庫的
     * UX_gcb_session_member 篩選式唯一索引噴出 DataIntegrityViolationException 更好——
     * 那個唯一索引是最後一道防線（防併發），不是第一道防線。
     */
    Optional<GroupClassBooking> findBySessionIdAndMemberIdAndStatus(Integer sessionId, Integer memberId,
            GroupClassBookingStatus status);
}