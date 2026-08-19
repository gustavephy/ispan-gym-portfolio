package tw.com.ispan.gym.dto.employee;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import tw.com.ispan.gym.enums.employee.EmployeeRole;

/** 角色調整：把角色設定為指定值。同角色（沒有變化）由 Service 判斷回 409。 */
@Getter
@Setter
public class AdminEmployeeRoleUpdateRequest {

    @NotNull(message = "員工角色為必填")
    private EmployeeRole role;
}
