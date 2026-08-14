package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.coach.GroupClassBookingStatus;

@Data
@AllArgsConstructor
public class GroupClassBookingResponse {
    private Integer id;
    private Integer sessionId;
    private String courseName;
    private String roomName;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private GroupClassBookingStatus status;
}