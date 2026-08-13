package tw.com.ispan.gym.dto.coach;


import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.coach.SlotStatus;

@Data
@AllArgsConstructor
public class CoachSlotResponse  {
    private Integer id;
    private Integer trainerId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private SlotStatus status;
}
