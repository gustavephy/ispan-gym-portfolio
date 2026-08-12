package tw.com.ispan.gym.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.CoachSlot;
import tw.com.ispan.gym.enums.SlotStatus;

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
         * 查詢某位教練的所有時段，依開始時間排序。
         * 注意：不會過濾任何 status，CANCELLED／BOOKED／AVAILABLE 都會一起回傳，
         * 前端若只想顯示「還能用」的時段，要自己依 status 篩選。
         */
        List<CoachSlot> findByTrainerIdOrderByStartAtAsc(
                        Integer trainerId);

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
         * 不像 {@link #findByTrainerIdOrderByStartAtAsc} 是給教練本人管理用、需要看到所有狀態。
         * 直接在查詢條件過濾，避免撈出不需要的資料再用 Java 篩選。
         */
        List<CoachSlot> findByTrainerIdAndStatusOrderByStartAtAsc(Integer trainerId, SlotStatus status);
}
