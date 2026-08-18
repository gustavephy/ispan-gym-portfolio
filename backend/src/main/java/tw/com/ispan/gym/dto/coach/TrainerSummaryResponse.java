package tw.com.ispan.gym.dto.coach;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TrainerSummaryResponse {
    private Integer trainerId;
    private String name;
    private String specialty;
    private String experience;
}
