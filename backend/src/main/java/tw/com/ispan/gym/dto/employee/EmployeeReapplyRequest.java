package tw.com.ispan.gym.dto.employee;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class EmployeeReapplyRequest {

    @NotBlank(message = "Email為必填")
    @Email(message = "Email格式不正確")
    private String email;

    @NotBlank(message = "密碼為必填")
    @ToString.Exclude
    private String password; // 驗證本人用，不會拿來變更密碼

    @NotBlank(message = "名字為必填")
    @Size(max = 50, message = "名字不可超過50個字元")
    private String firstName;

    @NotBlank(message = "姓氏為必填")
    @Size(max = 50, message = "姓氏不可超過50個字元")
    private String lastName;

    @NotBlank(message = "電話為必填")
    @Size(max = 10, message = "電話不可超過10個字元")
    @Pattern(regexp = "^09\\d{8}$", message = "電話格式不正確，請輸入09開頭的10碼手機號碼")
    private String phone;

    @NotNull(message = "到職日為必填")
    private LocalDate hireDate;
}
