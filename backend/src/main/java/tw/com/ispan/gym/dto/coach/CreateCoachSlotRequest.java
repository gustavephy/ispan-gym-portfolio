package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 建立教練時段的請求資料。
 *
 * <p>
 * 跟 {@link UpdateCoachSlotRequest} 分成兩個檔案而不是共用一份，
 * 是因為兩者的欄位其實不完全一樣——修改時多一個 acknowledgeExistingBooking，
 * 建立時用不到（新建立的時段不可能已經有人預約），欄位一旦不同，就不該硬塞在同一個 DTO 裡。
 */
@Data
public class CreateCoachSlotRequest {
    @NotNull(message = "開始時間不可為空")
    @Future(message = "開始時間必須是未來時間")
    private LocalDateTime startAt;

    @NotNull(message = "結束時間不可為空")
    @Future(message = "結束時間必須是未來時間")
    private LocalDateTime endAt;

    /**
     * 跨欄位驗證：結束時間要晚於開始時間。
     * 這種「比較兩個欄位」的規則，單一欄位的 @NotNull/@Future 做不到，
     * 要用 @AssertTrue 搭配一個回傳 boolean 的方法才能表達。
     */
    @AssertTrue(message = "結束時間必須晚於開始時間")
    private boolean isEndAfterStart() {
        return startAt == null || endAt == null || endAt.isAfter(startAt);
    }
}
