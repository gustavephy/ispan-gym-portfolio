package tw.com.ispan.gym.common.exception;

import java.time.LocalDateTime;

public record ApiErrorResponse(String code, String message, LocalDateTime timestamp) {}

