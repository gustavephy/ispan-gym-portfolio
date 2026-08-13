package tw.com.ispan.gym.dto.account;

public record LoginResponse(String accessToken, Integer accountId, String email) {}
