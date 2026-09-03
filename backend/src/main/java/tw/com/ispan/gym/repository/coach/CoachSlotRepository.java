package tw.com.ispan.gym.repository.coach;


import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.coach.CoachSlot;

public interface CoachSlotRepository extends JpaRepository<CoachSlot, Integer> {

        /**
         * 教練時段使用率聚合（營運報表用）：以教練為單位彙總期間內開放時段數與被預約數。
         * 取消的時段不計；半開區間 [from, to)，時間軸為時段開始時間。
         */
        @Query("""
                        SELECT new tw.com.ispan.gym.dto.report.TrainerSlotRow(
                            CONCAT(COALESCE(t.lastName, ''), COALESCE(t.firstName, '')),
                            COUNT(s), SUM(CASE WHEN s.status = 'BOOKED' THEN 1L ELSE 0L END))
                        FROM CoachSlot s JOIN s.trainer t
                        WHERE s.startAt >= :from AND s.startAt < :to AND s.status <> 'CANCELLED'
                        GROUP BY t.id, t.lastName, t.firstName
                        ORDER BY COUNT(s) DESC
                        """)
        List<tw.com.ispan.gym.dto.report.TrainerSlotRow> aggregateUtilization(
                        @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to);

        /**
         * 建立新時段時的重疊檢查：同一位教練，新時段的時間範圍是否跟既有的（未取消）時段有交集。
         * 排除 CANCELLED，因為已取消的時段不該再佔用這個時間點。
         */
        @Query("SELECT s FROM CoachSlot s WHERE s.trainer.id = :trainerId AND s.startAt < :endAt AND s.endAt > :startAt AND s.status <> 'CANCELLED'")
        List<CoachSlot> findOverlapping(
                        @Param("trainerId") Integer trainerId,
                        @Param("startAt") LocalDateTime startAt,
                        @Param("endAt") LocalDateTime endAt);

        /**
         * 修改時段時的重疊檢查，邏輯跟 {@link #findOverlapping} 相同，
         * 但多了 excludeId：修改時要把「這筆時段自己原本的時間」排除在比對之外，
         * 不然新時間一定會跟自己的舊資料被判定成重疊，導致永遠改不了。
         */
        @Query("SELECT s FROM CoachSlot s WHERE s.trainer.id = :trainerId AND s.id <> :excludeId " +
                        "AND s.startAt < :endAt AND s.endAt > :startAt AND s.status <> 'CANCELLED'")
        List<CoachSlot> findOverlappingExcluding(
                        @Param("trainerId") Integer trainerId,
                        @Param("excludeId") Integer excludeId,
                        @Param("startAt") LocalDateTime startAt,
                        @Param("endAt") LocalDateTime endAt);

        /**
         * 會員瀏覽某教練可預約時段用的分頁查詢，固定只查 AVAILABLE（寫死在查詢裡，不開放呼叫端
         * 指定其他狀態）——這是公開、免登入的端點，讓外部指定狀態沒有實益，還會增加日後不小心
         * 查出 BOOKED／CANCELLED 時段洩漏給陌生人的風險。可選 dateFrom／dateTo 縮小時間範圍。
         */
        @Query("""
                        SELECT s FROM CoachSlot s
                        WHERE s.trainer.id = :trainerId
                            AND s.status = tw.com.ispan.gym.enums.coach.SlotStatus.AVAILABLE
                            AND (:startAtFrom IS NULL OR s.startAt >= :startAtFrom)
                            AND (:startAtTo IS NULL OR s.startAt < :startAtTo)
                        """)
        Page<CoachSlot> searchAvailableSlots(@Param("trainerId") Integer trainerId,
                        @Param("startAtFrom") LocalDateTime startAtFrom,
                        @Param("startAtTo") LocalDateTime startAtTo,
                        Pageable pageable);

        /**
         * 分頁查詢某位教練的所有時段（含所有狀態），排序交給呼叫端組出的 Pageable 決定。
         * 不需要搜尋參數，衍生查詢方法名稱就能處理，不用寫 JPQL。
         */
        Page<CoachSlot> findByTrainerId(Integer trainerId, Pageable pageable);

        /**
         * 分頁查詢某位教練的所有時段，可依畫面上顯示的狀態與開始時間區間篩選。
         * status 對應前端「我的排班」表格實際顯示的四種狀態，不是 SlotStatus 本身的三個值：
         * AVAILABLE（可預約，對應 s.status=AVAILABLE）、CANCELLED（已取消，對應
         * s.status=CANCELLED）、PENDING（已預約待核銷，對應 s.status=BOOKED 且該時段目前有
         * PENDING 的預約）、COMPLETED（已完成，對應 s.status=BOOKED 且該時段目前有 COMPLETED
         * 的預約）——後兩者只看 status 欄位分不出來，需要額外用 EXISTS 檢查關聯的 CoachBooking。
         * status／startAtFrom／startAtTo 皆為 null 代表不篩選該條件。
         */
        @Query("""
                        SELECT s FROM CoachSlot s
                        WHERE s.trainer.id = :trainerId
                                AND (:startAtFrom IS NULL OR s.startAt >= :startAtFrom)
                                AND (:startAtTo IS NULL OR s.startAt < :startAtTo)
                                AND (
                                        :status IS NULL
                                        OR (:status = 'AVAILABLE' AND s.status = tw.com.ispan.gym.enums.coach.SlotStatus.AVAILABLE)
                                        OR (:status = 'CANCELLED' AND s.status = tw.com.ispan.gym.enums.coach.SlotStatus.CANCELLED)
                                        OR (:status = 'PENDING' AND s.status = tw.com.ispan.gym.enums.coach.SlotStatus.BOOKED
                                                AND EXISTS (SELECT 1 FROM CoachBooking b WHERE b.coachSlot = s
                                                        AND b.status = tw.com.ispan.gym.enums.coach.CoachBookingStatus.PENDING))
                                        OR (:status = 'COMPLETED' AND s.status = tw.com.ispan.gym.enums.coach.SlotStatus.BOOKED
                                                AND EXISTS (SELECT 1 FROM CoachBooking b WHERE b.coachSlot = s
                                                        AND b.status = tw.com.ispan.gym.enums.coach.CoachBookingStatus.COMPLETED))
                                )
                        """)
        Page<CoachSlot> searchMySlots(@Param("trainerId") Integer trainerId, @Param("status") String status,
                        @Param("startAtFrom") LocalDateTime startAtFrom, @Param("startAtTo") LocalDateTime startAtTo,
                        Pageable pageable);

        /**
         * 教練停用前檢查用：名下還有多少「未完成」時段——排除 CANCELLED，且結束時間仍在未來。
         * 只看 status 不夠：AVAILABLE/BOOKED 都沒有到期自動轉態，早已過去卻沒人清理的舊時段
         * 不該繼續擋著停用，故額外疊加 endAt > now（見 trainer_management_next_phase 規格基準）。
         */
        @Query("SELECT COUNT(s) FROM CoachSlot s WHERE s.trainer.id = :trainerId AND s.status <> 'CANCELLED' AND s.endAt > :now")
        int countOpenByTrainerId(@Param("trainerId") Integer trainerId, @Param("now") LocalDateTime now);
}
