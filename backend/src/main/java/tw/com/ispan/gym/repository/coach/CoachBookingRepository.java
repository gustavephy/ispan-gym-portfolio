package tw.com.ispan.gym.repository.coach;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.coach.CoachBooking;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;

public interface CoachBookingRepository extends JpaRepository<CoachBooking, Integer> {
    /**
     * 分頁查詢某會員的所有預約紀錄，供會員查看自己的預約歷史。排序交給呼叫端組出的
     * Pageable 決定（依 coachSlot.startAt 新到舊），不需要搜尋參數，衍生查詢方法名稱就能處理。
     */
    Page<CoachBooking> findByMemberId(Integer memberId, Pageable pageable);

    /**
     * 查詢某時段目前是否有指定狀態的預約。
     * 回傳 Optional 而非 List 是刻意反映資料庫層的保證：過濾式唯一索引
     * UX_coach_bookings_active_slot 已確保同一時段最多只有一筆非 CANCELLED 的預約，
     * 不會出現多筆，用 List 反而會讓呼叫端誤以為要處理多筆的情況。
     */
    Optional<CoachBooking> findByCoachSlotIdAndStatus(Integer coachSlotId, CoachBookingStatus status);

    /**
     * 查詢某時段目前那筆非指定狀態的預約（用來排除 CANCELLED，找出 PENDING 或 COMPLETED 的那一筆）。
     * 回傳 Optional 而非 List 理由同 {@link #findByCoachSlotIdAndStatus}：過濾式唯一索引
     * UX_coach_bookings_active_slot 已確保同一時段最多只有一筆非 CANCELLED 的預約。
     */
    Optional<CoachBooking> findByCoachSlotIdAndStatusNot(Integer coachSlotId, CoachBookingStatus status);

    /**
     * 教練停用前檢查用：名下還有多少「未完成」預約。CoachBookingStatus 只有
     * PENDING／COMPLETED／CANCELLED，沒有 CONFIRMED，故只看 PENDING（見
     * trainer_management_next_phase 規格基準的核對結果）。教練是透過 coachSlot 關聯，
     * 沒有直接欄位可用衍生查詢方法名稱表達，故手寫 JPQL。
     */
    @Query("SELECT COUNT(b) FROM CoachBooking b WHERE b.coachSlot.trainer.id = :trainerId AND b.status = :status")
    long countByTrainerIdAndStatus(@Param("trainerId") Integer trainerId, @Param("status") CoachBookingStatus status);
}