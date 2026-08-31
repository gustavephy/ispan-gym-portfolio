package tw.com.ispan.gym.dto.employee;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * ADMIN 代改員工個資：與 EmployeeProfileUpdateRequest 刻意分開、不共用——這支多了
 * hireDate，且日後兩邊權限範圍分岔時不會互相誤開欄位。
 * 不含 role／status，兩者走各自獨立端點。PUT 為完整覆蓋語意，全部欄位皆必填
 * （Employee 這四個欄位本身在 entity 上都是 NOT NULL，不像 Member 有選填欄位傳 null 清空的情境）。
 * phone 與 EmployeeProfileUpdateRequest 一致：09 開頭、固定 10 碼（見 @Pattern）。
 */
@Getter
@Setter
public class AdminEmployeeUpdateRequest {

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
