package tw.com.ispan.gym.dto.auth;

public record LoginResponse(String accessToken, Integer accountId, String email) {}
