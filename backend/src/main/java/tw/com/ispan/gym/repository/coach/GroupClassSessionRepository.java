package tw.com.ispan.gym.repository.coach;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.coach.GroupClassSession;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;

public interface GroupClassSessionRepository extends JpaRepository<GroupClassSession, Integer> {
    /** 查詢還沒開始、狀態仍開放的場次，依開始時間由近到遠排序，方便會員看快要開始的課在前面。 */
    List<GroupClassSession> findByStatusAndStartAtAfterOrderByStartAtAsc(GroupClassSessionStatus status,
            LocalDateTime after);

    @Query("SELECT s FROM GroupClassSession s WHERE s.roomName = :roomName AND s.startAt < :endAt AND s.endAt > :startAt AND s.status <> 'CANCELLED'")
    List<GroupClassSession> findOverlappingByRoom(
            @Param("roomName") String roomName,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);
}