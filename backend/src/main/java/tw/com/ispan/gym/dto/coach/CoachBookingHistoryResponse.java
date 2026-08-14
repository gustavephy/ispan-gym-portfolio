package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;

/**
 * 會員查詢自己預約歷史列表時的回應資料。
 *
 * <p>
 * remainingCourseSnapshot：這筆預約「目前狀態」發生當下的額度快照——
 * 還是 PENDING 就是訂課當下(BOOK 事件)的餘額；已 COMPLETED/CANCELLED
 * 就是那個終結事件(CHECKIN/CANCEL_REFUND/CANCEL_CONSUME)當下的餘額。
 * 來源是 SessionDeductionLog.afterRemaining，不是即時查詢 quota 目前的值，
 * 因此同一包額度底下的多筆預約，這個數字可能各自不同——這跟
 * {@link CoachBookingResponse#getRemainingCourse()}（即時餘額）語意不同，不要混用。
 *
 * <p>
 * 若此筆預約沒有對應的稽核紀錄（稽核寫入功能上線前建立的舊資料），
 * 這個欄位會是 null，前端請處理成「無歷史資料」，不要當成 0 堂看待。
 */
@Data
@AllArgsConstructor
public class CoachBookingHistoryResponse {
    private Integer id;
    private Integer coachSlotId;
    private Integer trainerId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private CoachBookingStatus status;
    private String checkInKey;
    private Integer remainingCourseSnapshot;
}