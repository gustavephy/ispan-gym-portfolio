package tw.com.ispan.gym.repository.coach;

import java.util.Optional;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    List<GroupClassBooking> findBySessionIdAndStatus(Integer sessionId, GroupClassBookingStatus status);

    /**
     * 分頁查詢某會員的所有團課報名紀錄，可依課程名稱或教室搜尋，供會員查看自己的報名歷史。
     * 排序交給呼叫端組出的 Pageable 決定（依場次開始時間新到舊），不在這裡寫 ORDER BY，
     * 理由同 {@code GroupCourseRepository#searchCourses}。
     */
    @Query("""
            SELECT b FROM GroupClassBooking b
            JOIN b.session s
            JOIN s.course c
            WHERE b.member.id = :memberId
                AND (:search IS NULL OR :search = ''
                    OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(s.roomName) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<GroupClassBooking> searchMyBookings(@Param("memberId") Integer memberId, @Param("search") String search,
            Pageable pageable);
}