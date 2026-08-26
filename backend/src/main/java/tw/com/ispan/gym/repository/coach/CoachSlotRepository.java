package tw.com.ispan.gym.repository.coach;


import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.coach.CoachSlot;
import tw.com.ispan.gym.enums.coach.SlotStatus;

public interface CoachSlotRepository extends JpaRepository<CoachSlot, Integer> {

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
         * 查詢某教練指定狀態的時段，依開始時間排序。
         * 用在會員瀏覽可預約時段（searchSlots），只需要 AVAILABLE，
         * 不像 {@link #findByTrainerId} 是給教練本人管理用、需要看到所有狀態。
         * 直接在查詢條件過濾，避免撈出不需要的資料再用 Java 篩選。
         */
        @Query("SELECT s FROM CoachSlot s WHERE s.trainer.id = :trainerId AND s.status = :status ORDER BY s.startAt ASC")
        List<CoachSlot> findByTrainerIdAndStatusOrderByStartAtAsc(@Param("trainerId") Integer trainerId, @Param("status") SlotStatus status);

        /**
         * 分頁查詢某位教練的所有時段（含所有狀態），排序交給呼叫端組出的 Pageable 決定。
         * 不需要搜尋參數，衍生查詢方法名稱就能處理，不用寫 JPQL。
         */
        Page<CoachSlot> findByTrainerId(Integer trainerId, Pageable pageable);

        /**
         * 教練停用前檢查用：名下還有多少「未完成」時段——排除 CANCELLED，且結束時間仍在未來。
         * 只看 status 不夠：AVAILABLE/BOOKED 都沒有到期自動轉態，早已過去卻沒人清理的舊時段
         * 不該繼續擋著停用，故額外疊加 endAt > now（見 trainer_management_next_phase 規格基準）。
         */
        @Query("SELECT COUNT(s) FROM CoachSlot s WHERE s.trainer.id = :trainerId AND s.status <> 'CANCELLED' AND s.endAt > :now")
        int countOpenByTrainerId(@Param("trainerId") Integer trainerId, @Param("now") LocalDateTime now);
}
