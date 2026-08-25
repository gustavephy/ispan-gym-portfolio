package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

public record MemberCourseQuotaResponse(
    Integer id,
    String courseName,
    Integer remainingCourse,
    Integer reservedCourse,
    Integer usedCourse,
    LocalDateTime expiryDate,
    Integer productId,
    TrainerInfo trainer
) {
    public record TrainerInfo(
        Integer id,
        String firstName,
        String lastName
    ) {}
}
