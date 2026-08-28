package tw.com.ispan.gym.repository.coach;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.coach.SessionDeductionLog;

public interface SessionDeductionLogRepository extends JpaRepository<SessionDeductionLog, Integer> {

    /**
     * 依一批 coachBookingId 撈出對應的稽核紀錄，一次撈齊、避免對每筆預約各查一次（N+1）。
     * 依 id 由新到舊排序，呼叫端可以直接取每組第一筆當「最新一筆」，不用另外比較時間戳記。
     */
    List<SessionDeductionLog> findByCoachBookingIdInOrderByIdDesc(List<Integer> coachBookingIds);

    /**
     * 分頁查詢某會員的所有堂數異動稽核紀錄，可依事件類型與建立時間區間篩選，供管理員審計。
     * 排序交給呼叫端組出的 Pageable 決定（依 createdAt 新到舊），不在這裡寫 ORDER BY，
     * 理由同 {@code CoachBookingRepository#searchMyBookings}。
     * eventType／createdAtFrom／createdAtTo 皆為 null 代表不篩選該條件。
     */
    @Query("""
            SELECT l FROM SessionDeductionLog l
            WHERE l.member.id = :memberId
                AND (:eventType IS NULL OR l.eventType = :eventType)
                AND (:createdAtFrom IS NULL OR l.createdAt >= :createdAtFrom)
                AND (:createdAtTo IS NULL OR l.createdAt < :createdAtTo)
            """)
    Page<SessionDeductionLog> searchByMember(@Param("memberId") Integer memberId,
            @Param("eventType") String eventType,
            @Param("createdAtFrom") LocalDateTime createdAtFrom,
            @Param("createdAtTo") LocalDateTime createdAtTo,
            Pageable pageable);
}
