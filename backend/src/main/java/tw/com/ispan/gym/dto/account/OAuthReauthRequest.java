package tw.com.ispan.gym.dto.account;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** LINK 發起與 UNLINK 都要求重新驗證目前密碼，共用同一個請求形狀 */
@Getter
@Setter
public class OAuthReauthRequest {

    @NotBlank(message = "currentPassword 為必填")
    private String currentPassword;
}
