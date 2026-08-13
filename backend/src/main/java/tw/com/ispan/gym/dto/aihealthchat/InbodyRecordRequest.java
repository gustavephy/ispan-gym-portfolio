package tw.com.ispan.gym.dto.aihealthchat;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InbodyRecordRequest(
        @NotNull @PastOrPresent LocalDate updateDate,

        @DecimalMin("50.00") @DecimalMax("250.00") BigDecimal height,

        @DecimalMin("20.00") @DecimalMax("300.00") BigDecimal weight,

        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal bodyFat,

        @DecimalMin("0.00") @DecimalMax("150.00") BigDecimal muscleMass,

        @DecimalMin("10.00") @DecimalMax("80.00") BigDecimal bmi) {
}
