package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GroupClassSessionResponse {
    private Integer id;
    private String courseName;
    private String roomName;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer capacity;
    private Integer remainingSeats;
}