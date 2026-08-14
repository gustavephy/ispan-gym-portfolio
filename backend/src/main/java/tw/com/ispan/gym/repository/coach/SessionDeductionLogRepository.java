package tw.com.ispan.gym.repository.coach;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.coach.SessionDeductionLog;

public interface SessionDeductionLogRepository extends JpaRepository<SessionDeductionLog, Integer> {

    /**
     * 依一批 coachBookingId 撈出對應的稽核紀錄，一次撈齊、避免對每筆預約各查一次（N+1）。
     * 依 id 由新到舊排序，呼叫端可以直接取每組第一筆當「最新一筆」，不用另外比較時間戳記。
     */
    List<SessionDeductionLog> findByCoachBookingIdInOrderByIdDesc(List<Integer> coachBookingIds);
}
