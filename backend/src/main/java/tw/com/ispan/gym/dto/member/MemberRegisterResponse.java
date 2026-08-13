package tw.com.ispan.gym.dto.member;

/**
 * 刻意不帶任何既有會員欄位（id/姓名/電話/狀態等）——PENDING 重送與軟刪除復活這兩個分支
 * 都是對「可能不是本人」的請求者回應，帶真實資料等於讓人拿 email 就能撈出對方個資。
 */
public record MemberRegisterResponse(String email, String message) {

    public static MemberRegisterResponse of(String email, String message) {
        return new MemberRegisterResponse(email, message);
    }
}
