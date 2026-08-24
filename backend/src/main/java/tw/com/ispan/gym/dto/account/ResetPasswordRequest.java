package tw.com.ispan.gym.dto.account;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import tw.com.ispan.gym.validation.ValidPassword;

@Getter
@Setter
@ToString
public class ResetPasswordRequest {

    @NotBlank(message = "Token為必填")
    @ToString.Exclude
    private String token;

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
