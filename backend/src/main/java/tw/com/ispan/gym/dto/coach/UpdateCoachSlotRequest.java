package tw.com.ispan.gym.dto.coach;


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
}