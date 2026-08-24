package tw.com.ispan.gym.dto.employee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 員工自助修改：僅能修改電話。姓名屬正式人事資料，只能由 ADMIN 透過 AdminEmployeeUpdateRequest
 * 修改；role／status／hireDate／審核欄位／Trainer 身分／Account 欄位一律不在這支 DTO 內。
 */
@Getter
@Setter
public class EmployeeProfileUpdateRequest {

    @NotBlank(message = "電話為必填")
    @Size(max = 20, message = "電話不可超過20個字元")
    private String phone;
}
