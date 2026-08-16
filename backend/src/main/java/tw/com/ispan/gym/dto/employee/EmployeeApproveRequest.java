package tw.com.ispan.gym.dto.employee;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmployeeApproveRequest {

    @Size(max = 500, message = "備註不可超過500個字元")
    private String reviewNote; // 選填
}
