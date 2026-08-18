package tw.com.ispan.gym.dto.member;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import tw.com.ispan.gym.enums.member.Gender;

/**
 * ADMIN 代改會員個資：與 MemberProfileUpdateRequest 欄位目前相同，
 * 但刻意獨立成兩個型別、不共用——避免日後兩邊權限範圍分岔時誤開欄位。
 * 不含 status，狀態異動走獨立的停用／恢復端點。PUT 為完整覆蓋語意。
 */
@Getter
@Setter
public class AdminMemberUpdateRequest {

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

    @Size(max = 50, message = "緊急聯絡人不可超過50個字元")
    private String emergencyContact;

    @Size(max = 20, message = "緊急聯絡電話不可超過20個字元")
    private String emergencyPhone;

    @Size(max = 10, message = "郵遞區號不可超過10個字元")
    private String zipCode;

    @Size(max = 50, message = "縣市不可超過50個字元")
    private String city;

    @Size(max = 50, message = "鄉鎮市區不可超過50個字元")
    private String district;

    @Size(max = 255, message = "詳細地址不可超過255個字元")
    private String address;
}
