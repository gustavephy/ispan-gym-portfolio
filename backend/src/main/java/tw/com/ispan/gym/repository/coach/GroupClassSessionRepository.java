package tw.com.ispan.gym.repository.coach;

import java.time.LocalDateTime;
import java.util.List;

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
}