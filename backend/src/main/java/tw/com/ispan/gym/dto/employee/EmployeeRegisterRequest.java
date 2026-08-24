package tw.com.ispan.gym.dto.employee;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import tw.com.ispan.gym.validation.ValidPassword;

@Getter
@Setter
@ToString
public class EmployeeRegisterRequest {

    @NotBlank(message = "名字為必填")
    @Size(max = 50, message = "名字不可超過50個字元")
    private String firstName;

    @NotBlank(message = "姓氏為必填")
    @Size(max = 50, message = "姓氏不可超過50個字元")
    private String lastName;

    @NotBlank(message = "Email為必填")
    @Email(message = "Email格式不正確")
    @Size(max = 100, message = "Email不可超過100個字元")
    private String email;

    @NotBlank(message = "電話為必填")
    @Size(max = 20, message = "電話不可超過20個字元")
    private String phone;

    @NotBlank(message = "密碼為必填")
    @ValidPassword
    @ToString.Exclude
    private String password;

    @NotNull(message = "到職日為必填")
    private LocalDate hireDate;
}
