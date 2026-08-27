package tw.com.ispan.gym.repository.coach;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.coach.GroupClassSession;
import tw.com.ispan.gym.enums.coach.ClassRoom;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;

public interface GroupClassSessionRepository extends JpaRepository<GroupClassSession, Integer> {

        /**
         * 查詢某教室在指定時間範圍內、狀態未取消的重疊場次。
         *
         * 注意：本方法只做「嚴格時間重疊」判斷，不內建任何緩衝時間。
         * 若業務規則需要清場緩衝（例如兩堂課之間至少間隔 N 分鐘），
         * 呼叫端必須自行把 startAt/endAt 往外撐大後再傳入，
         * 見 GroupClassService.ROOM_CLEANUP_BUFFER_MINUTES 的用法。
         */
        @Query("SELECT s FROM GroupClassSession s WHERE s.roomName = :roomName AND s.startAt < :endAt AND s.endAt > :startAt AND s.status <> 'CANCELLED'")
        List<GroupClassSession> findOverlappingByRoom(
                        @Param("roomName") ClassRoom roomName,
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
        /**
         * roomName 是 enum（見 {@link tw.com.ispan.gym.enums.coach.ClassRoom}），JPQL 對 enum
         * 欄位套字串函式（LOWER/LIKE）並不保證能編譯，故改由呼叫端（GroupClassService）先把
         * search 字串對照 ClassRoom 的中文顯示名稱算出三個固定 boolean，這裡只做字面值相等比較。
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
                                OR (:matchesYoga = true AND s.roomName = 'YOGA_ROOM')
                                OR (:matchesBoxing = true AND s.roomName = 'BOXING_ROOM')
                                OR (:matchesWeight = true AND s.roomName = 'WEIGHT_ROOM'))
                        """)
        Page<GroupClassSession> searchUpcomingSessions(@Param("status") GroupClassSessionStatus status,
                        @Param("after") LocalDateTime after, @Param("search") String search,
                        @Param("matchesYoga") boolean matchesYoga, @Param("matchesBoxing") boolean matchesBoxing,
                        @Param("matchesWeight") boolean matchesWeight, Pageable pageable);

        /**
         * 教練停用前檢查用：名下還有多少「未完成」團課場次。教練綁在 GroupCourse 主檔，
         * 場次本身沒有 trainer 欄位（代課設計已捨棄），故先 JOIN course 取得 trainer.id。
         * GroupClassSessionStatus 沒有 COMPLETED，只有 SCHEDULED／CANCELLED，故用
         * status=SCHEDULED AND endAt > now 判斷「還沒結束」——不能用 startAt（見公開列表既有的
         * upcoming 查詢），那是「還沒開始」，會漏掉「已經開始、還沒結束」的場次（例如
         * 14:00-15:00 的場次，14:30 停用教練時仍應視為未完成業務，見 code review 發現，
         * 2026-08-19，trainer_management_next_phase 規格基準）。
         */
        @Query("SELECT COUNT(s) FROM GroupClassSession s JOIN s.course c "
                        + "WHERE c.trainer.id = :trainerId AND s.status = :status AND s.endAt > :after")
        int countByTrainerIdAndStatusAndEndAtAfter(@Param("trainerId") Integer trainerId,
                        @Param("status") GroupClassSessionStatus status, @Param("after") LocalDateTime after);
}