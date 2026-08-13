package tw.com.ispan.gym.dto.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendVerificationRequest(
        @NotBlank(message = "Email為必填") @Email(message = "Email格式不正確") String email) {}
