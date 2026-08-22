package tw.com.ispan.gym.dto.account;

/** @param code 一次性交換碼；前端接著呼叫 POST /api/auth/oauth/exchange 換 JWT */
public record OAuthRegistrationCompleteResponse(String code) {}
