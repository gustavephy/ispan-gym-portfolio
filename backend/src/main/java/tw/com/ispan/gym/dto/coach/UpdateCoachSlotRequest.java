package tw.com.ispan.gym.dto.coach;

import java.time.Duration;
import java.time.LocalDateTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改教練時段的請求資料。
 * 欄位結構跟 {@link CreateCoachSlotRequest} 幾乎一樣，差異只有多了
 * {@link #acknowledgeExistingBooking}——這是分開建立這個檔案的唯一原因。
 */
@Data
public class UpdateCoachSlotRequest {

    @NotNull(message = "開始時間不可為空")
    @Future(message = "開始時間必須是未來時間")
    private LocalDateTime startAt;

    @NotNull(message = "結束時間不可為空")
    @Future(message = "結束時間必須是未來時間")
    private LocalDateTime endAt;

    /**
     * 若這個時段目前已被會員預約（BOOKED），教練必須明確把這個欄位設為 true，
     * 修改才會真的執行；預設 false，代表「還沒確認」。
     * 這是兩階段確認機制的一部分：第一次送出（false 或沒帶）若時段是 BOOKED，後端回 409，
     * 前端跳確認視窗，教練確認後帶著 true 重送，才真的改時間。
     */
    private boolean acknowledgeExistingBooking = false;

    @AssertTrue(message = "結束時間必須晚於開始時間")
    private boolean isEndAfterStart() {
        return startAt == null || endAt == null || endAt.isAfter(startAt);
    }

    /**
     * 時段長度限制：預設一堂課 60 分鐘，最長不超過 90 分鐘。
     * （避免建出像 1 分鐘、或跨好幾天這種不合理的時段）。
     * 跟 isEndAfterStart() 一樣，startAt/endAt 是 null 時直接放行，
     * 交給各自的 @NotNull 去擋，這裡不重複報錯。
     */
    @AssertTrue(message = "時段長度需介於 60 分鐘至 90 分鐘之間")
    private boolean isDurationValid() {
        if (startAt == null || endAt == null) {
            return true;
        }
        long minutes = Duration.between(startAt, endAt).toMinutes();
        return minutes >= 60 && minutes <= 90;
    }
}