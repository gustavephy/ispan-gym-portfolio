package tw.com.ispan.gym.dto.coach;

import lombok.Data;

/** 教練核銷時，body 帶的核銷碼。bookingId 走路徑參數，不放這裡。 */
@Data
public class CheckInRequest {
    private String checkInKey;
}