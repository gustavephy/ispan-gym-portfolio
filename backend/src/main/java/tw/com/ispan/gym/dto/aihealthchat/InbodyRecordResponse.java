package tw.com.ispan.gym.dto.aihealthchat;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InbodyRecordResponse(
        Integer id,
        LocalDate updateDate,
        BigDecimal height,
        BigDecimal weight,
        BigDecimal bodyFat,
        BigDecimal muscleMass,
        BigDecimal bmi) {
}
