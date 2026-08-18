package tw.com.ispan.gym.dto.coach;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 教練核銷時，body 帶的核銷碼。bookingId 走路徑參數，不放這裡。 */
@Data
public class CheckInRequest {
    @NotBlank(message = "核銷碼不可為空")
    private String checkInKey;
}