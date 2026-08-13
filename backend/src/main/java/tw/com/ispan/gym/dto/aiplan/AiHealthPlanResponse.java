package tw.com.ispan.gym.dto.aiplan;

import java.time.LocalDateTime;

public record AiHealthPlanResponse(
        Integer id,
        Integer inbodyRecordId,
        String goal,
        String planContent,
        String modelName,
        LocalDateTime createdAt) {
}
