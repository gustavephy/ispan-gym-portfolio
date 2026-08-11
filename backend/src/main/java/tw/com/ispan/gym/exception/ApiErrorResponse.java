package tw.com.ispan.gym.exception;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiErrorResponse {
	private int code;
	private String message;
	private LocalDateTime timestamp;
}
