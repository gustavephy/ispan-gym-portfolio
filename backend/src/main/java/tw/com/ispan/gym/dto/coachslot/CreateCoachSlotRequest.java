package tw.com.ispan.gym.dto.coachslot;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateCoachSlotRequest {
    @NotNull(message = "開始時間不可為空")
    @Future(message = "開始時間必須是未來時間")
    private LocalDateTime startAt;

    @NotNull(message = "結束時間不可為空")
    @Future(message = "結束時間必須是未來時間")
    private LocalDateTime endAt;
}
