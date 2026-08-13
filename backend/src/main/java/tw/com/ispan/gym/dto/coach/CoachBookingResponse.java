package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;

/**
 * 預約成功後回傳給前端的資料。
 * remainingCourse 是扣款後（book() 執行完）的最新剩餘堂數，
 * 讓前端可以立刻顯示「這包額度還剩幾堂」，不用另外再查一次。
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