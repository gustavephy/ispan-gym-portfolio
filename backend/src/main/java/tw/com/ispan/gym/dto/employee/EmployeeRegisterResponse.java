package tw.com.ispan.gym.dto.employee;

/** 刻意不帶任何真實員工欄位——理由跟 MemberRegisterResponse 一樣，anonymous 端點不能靠 email 撈出他人資料 */
public record EmployeeRegisterResponse(String email, String message) {

    public static EmployeeRegisterResponse of(String email, String message) {
        return new EmployeeRegisterResponse(email, message);
    }
}
