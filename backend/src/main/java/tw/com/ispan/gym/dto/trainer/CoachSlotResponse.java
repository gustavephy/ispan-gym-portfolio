package tw.com.ispan.gym.dto.trainer;

import tw.com.ispan.gym.entity.trainer.Trainer;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.SlotStatus;

@Data
@AllArgsConstructor
public class CoachSlotResponse  {
    private Integer id;
    private Integer trainerId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private SlotStatus status;
}
