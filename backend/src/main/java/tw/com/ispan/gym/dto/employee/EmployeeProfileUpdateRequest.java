package tw.com.ispan.gym.dto.employee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 員工自助修改資料。可修改電話與姓名。
 */
@Getter
@Setter
public class EmployeeProfileUpdateRequest {

    @NotBlank(message = "姓氏為必填")
    @Size(max = 50, message = "姓氏不可超過50個字元")
    private String lastName;

    @NotBlank(message = "名字為必填")
    @Size(max = 50, message = "名字不可超過50個字元")
    private String firstName;

    @NotBlank(message = "電話為必填")
    @Size(max = 10, message = "電話不可超過10個字元")
    @Pattern(regexp = "^09\\d{8}$", message = "電話格式不正確，請輸入09開頭的10碼手機號碼")
    private String phone;

    private String profileImageUrl;
    private String specialty;
    private String experience;
    private String bio;
}
