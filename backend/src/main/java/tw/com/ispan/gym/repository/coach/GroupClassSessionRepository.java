package tw.com.ispan.gym.repository.coach;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.coach.GroupClassSession;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;

public interface GroupClassSessionRepository extends JpaRepository<GroupClassSession, Integer> {

        @Query("SELECT s FROM GroupClassSession s WHERE s.roomName = :roomName AND s.startAt < :endAt AND s.endAt > :startAt AND s.status <> 'CANCELLED'")
        List<GroupClassSession> findOverlappingByRoom(
                        @Param("roomName") String roomName,
                        @Param("startAt") LocalDateTime startAt,
                        @Param("endAt") LocalDateTime endAt);

        /**
         * 查詢還沒開始、狀態仍開放的場次，依開始時間由近到遠排序，方便會員看快要開始的課在前面。
         * 用 fetch-join 一次撈出相關的關聯（course -> trainer -> employee），避免 service 層在映射時造成 N+1
         * 問題。
         */
        @Query("SELECT s FROM GroupClassSession s "
                        + "JOIN FETCH s.course c "
                        + "JOIN FETCH c.trainer t "
                        + "JOIN FETCH t.employee e "
                        + "WHERE s.status = :status AND s.startAt > :after "
                        + "ORDER BY s.startAt ASC")
        List<GroupClassSession> findByStatusAndStartAtAfterWithCourseTrainerOrderByStartAtAsc(
                        @Param("status") GroupClassSessionStatus status,
                        @Param("after") LocalDateTime after);

        /**
         * 後台團課場次列表的分頁＋文字搜尋，比對課程名稱、授課教練姓名與教室，篩選邏輯沿用
         * {@link #findByStatusAndStartAtAfterWithCourseTrainerOrderByStartAtAsc}（status=SCHEDULED
         * 且 startAt 在指定時間之後）。用 JOIN FETCH 一次撈出 course/trainer/employee，
         * 避免 service 層映射時觸發 N+1；排序交給呼叫端組出的 Pageable 決定，不在這裡寫 ORDER BY。
         */
        @Query("""
                        SELECT s FROM GroupClassSession s
                        JOIN FETCH s.course c
                        JOIN FETCH c.trainer t
                        JOIN FETCH t.employee e
                        WHERE s.status = :status AND s.startAt > :after
                            AND (:search IS NULL OR :search = ''
                                OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
                                OR LOWER(CONCAT(e.firstName, e.lastName)) LIKE LOWER(CONCAT('%', :search, '%'))
                                OR LOWER(s.roomName) LIKE LOWER(CONCAT('%', :search, '%')))
                        """)
        Page<GroupClassSession> searchUpcomingSessions(@Param("status") GroupClassSessionStatus status,
                        @Param("after") LocalDateTime after, @Param("search") String search, Pageable pageable);
}