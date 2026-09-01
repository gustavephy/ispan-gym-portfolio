package tw.com.ispan.gym.dto.account;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import tw.com.ispan.gym.validation.ValidPassword;

/**
 * OAuth-only 帳號設定「第一組」本機密碼的請求。
 *
 * <p>刻意沒有 {@code currentPassword} 欄位——這種帳號本來就沒有可以重新驗證的舊密碼，
 * 已登入的 JWT 就是設定第一組密碼的身分證明（見
 * memory/google_oauth_phase_scope.md 第五批「最小方案」定案）。
 *
 * <p>密碼規則與 {@link ChangePasswordRequest} 共用同一個 {@link ValidPassword}：
 * 8–72 字元、含大小寫英文字母與數字、無空白、UTF-8 編碼不超過 72 bytes。
 */
@Getter
@Setter
@ToString
public class SetPasswordRequest {

    @NotBlank(message = "新密碼為必填")
    @ValidPassword
    @ToString.Exclude
    private String newPassword;

    @NotBlank(message = "確認密碼為必填")
    @ToString.Exclude
    private String confirmPassword;

    @AssertTrue(message = "新密碼與確認密碼不一致")
    private boolean isPasswordConfirmed() {
        return newPassword != null && newPassword.equals(confirmPassword);
    }
}
