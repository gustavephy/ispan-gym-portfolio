package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;

/**
 * 私教課單一動作（訂課／取消／核銷）完成後回傳給前端的資料。
 *
 * <p>
 * remainingCourse 代表「這次動作結束後」的最新餘額，時間點對應到這次異動當下，
 * 可視為該次操作的結果快照——僅適用於 createBooking／cancelBooking／checkIn
 * 這種「做完一個動作、立刻回應」的情境。
 *
 * <p>
 * 查詢歷史列表（{@code getMyBookings}）請改用 {@link CoachBookingHistoryResponse}，
 * 那邊的餘額語意不同（取的是每筆預約各自最新事件的快照，不是即時值），
 * 不要沿用這個 DTO，否則會重演一開始把兩種語意混在同一個欄位的問題。
 *
 * <p>
 * checkInKey：模組四（核銷）新增。只在預約建立當下產生一次，之後不會變動，
 * 取消/核銷後這個值還是會留在回傳資料裡（方便對帳查歷史），但實際核銷 API
 * 會另外檢查 status 是否為 PENDING，不是靠這個欄位存不存在來防重複核銷。
 */
@Data
@AllArgsConstructor
public class CoachBookingResponse {
    private Integer id;
    private Integer coachSlotId;
    private Integer trainerId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private CoachBookingStatus status;
    private String checkInKey;
    private Integer remainingCourse;
}