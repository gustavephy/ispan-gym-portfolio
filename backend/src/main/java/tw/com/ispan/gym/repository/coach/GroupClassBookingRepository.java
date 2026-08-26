package tw.com.ispan.gym.repository.coach;

import java.time.LocalDateTime;
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
     * 分頁查詢某會員的所有團課報名紀錄，可依課程名稱或教室搜尋、依狀態與場次開始時間區間篩選，
     * 供會員查看自己的報名歷史。排序交給呼叫端組出的 Pageable 決定（依場次開始時間新到舊），
     * 不在這裡寫 ORDER BY，理由同 {@code GroupCourseRepository#searchCourses}。
     * status／startAtFrom／startAtTo 皆為 null 代表不篩選該條件。
     */
    /**
     * roomName 是 enum（見 {@link tw.com.ispan.gym.enums.coach.ClassRoom}），JPQL 對 enum
     * 欄位套字串函式（LOWER/LIKE）並不保證能編譯，故改由呼叫端（GroupClassService）先把
     * search 字串對照 ClassRoom 的中文顯示名稱算出三個固定 boolean，這裡只做字面值相等比較。
     * 不用 {@code IN} 動態集合，是為了避免空集合綁定在不同 Hibernate 版本行為不一致的風險。
     */
    @Query("""
            SELECT b FROM GroupClassBooking b
            JOIN b.session s
            JOIN s.course c
            WHERE b.member.id = :memberId
                AND (:search IS NULL OR :search = ''
                    OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
                    OR (:matchesYoga = true AND s.roomName = 'YOGA_ROOM')
                    OR (:matchesBoxing = true AND s.roomName = 'BOXING_ROOM')
                    OR (:matchesWeight = true AND s.roomName = 'WEIGHT_ROOM'))
                AND (:status IS NULL OR b.status = :status)
                AND (:startAtFrom IS NULL OR s.startAt >= :startAtFrom)
                AND (:startAtTo IS NULL OR s.startAt < :startAtTo)
            """)
    Page<GroupClassBooking> searchMyBookings(@Param("memberId") Integer memberId, @Param("search") String search,
            @Param("matchesYoga") boolean matchesYoga, @Param("matchesBoxing") boolean matchesBoxing,
            @Param("matchesWeight") boolean matchesWeight,
            @Param("status") GroupClassBookingStatus status, @Param("startAtFrom") LocalDateTime startAtFrom,
            @Param("startAtTo") LocalDateTime startAtTo, Pageable pageable);
}