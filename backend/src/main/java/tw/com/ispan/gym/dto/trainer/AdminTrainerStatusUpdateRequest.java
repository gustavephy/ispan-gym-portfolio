package tw.com.ispan.gym.dto.trainer;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;

/** ADMIN 切換 Trainer 可服務狀態用（POST /api/admin/trainers/{trainerId}/status）。 */
@Getter
@Setter
public class AdminTrainerStatusUpdateRequest {

    @NotNull(message = "狀態為必填")
    private TrainerStatus status;
}
