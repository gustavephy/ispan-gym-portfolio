package tw.com.ispan.gym.dto.account;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import tw.com.ispan.gym.enums.member.Gender;

/**
 * 首次 Google 登入的補資料表單。email 與 Google subject 一律從 server-side 的
 * {@code OAuthRegistrationTicket} 取得，不接受前端回傳——這兩者是身分認定的
 * 依據，不能讓前端有機會偽造。
 */
@Getter
@Setter
@ToString
public class OAuthRegistrationCompleteRequest {

    @NotBlank(message = "registrationToken 為必填")
    @ToString.Exclude
    private String registrationToken;

    @NotBlank(message = "名字為必填")
    @Size(max = 50, message = "名字不可超過50個字元")
    private String firstName;

    @NotBlank(message = "姓氏為必填")
    @Size(max = 50, message = "姓氏不可超過50個字元")
    private String lastName;

    @NotBlank(message = "電話為必填")
    @Size(max = 10, message = "電話不可超過10個字元")
    @Pattern(regexp = "^09\\d{8}$", message = "電話格式不正確，請輸入台灣手機號碼，例如0912345678")
    private String phone;

    @Past(message = "生日是過去日期")
    private LocalDate dob;

    private Gender gender;
}
