package tw.com.ispan.gym.dto.account;

import jakarta.validation.constraints.NotBlank;

public record OAuthExchangeRequest(@NotBlank(message = "code 為必填") String code) {}
