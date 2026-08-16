package tw.com.ispan.gym.dto.employee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmployeeRejectRequest {

    @NotBlank(message = "拒絕原因為必填")
    @Size(max = 500, message = "拒絕原因不可超過500個字元")
    private String reason;
}
