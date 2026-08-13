package tw.com.ispan.gym.dto.aiplan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GeneratePlanRequest(
        @NotNull Integer inbodyRecordId,
        @NotBlank String goal) {
}
