package tw.com.ispan.gym.dto.coach;

import java.time.Duration;
import java.time.LocalDateTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateGroupClassSessionRequest {
    @NotNull(message = "團課課程不可為空")
    private Integer groupCourseId;

    @NotBlank(message = "教室不可為空")
    private String roomName;

    @NotNull(message = "開始時間不可為空")
    @Future(message = "開始時間必須是未來時間")
    private LocalDateTime startAt;

    @NotNull(message = "結束時間不可為空")
    @Future(message = "結束時間必須是未來時間")
    private LocalDateTime endAt;

    /** 不填就用該團課課程的 maxCapacity，讓管理層可以視實際教室彈性調整。 */
    private Integer capacity;

    @AssertTrue(message = "結束時間必須晚於開始時間")
    private boolean isEndAfterStart() {
        return startAt == null || endAt == null || endAt.isAfter(startAt);
    }

    /**
     * 場次長度限制：60 分鐘至 90 分鐘之間，寫法比照 CreateCoachSlotRequest
     * 的 isDurationValid()。startAt/endAt 是 null 時直接放行，交給各自的
     * @NotNull 去擋，這裡不重複報錯。
     */
    @AssertTrue(message = "場次長度需介於 60 分鐘至 90 分鐘之間")
    private boolean isDurationValid() {
        if (startAt == null || endAt == null) {
            return true;
        }
        long minutes = Duration.between(startAt, endAt).toMinutes();
        return minutes >= 60 && minutes <= 90;
    }
}