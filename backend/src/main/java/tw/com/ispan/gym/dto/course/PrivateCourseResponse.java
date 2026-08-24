package tw.com.ispan.gym.dto.course;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import tw.com.ispan.gym.dto.trainer.TrainerDto;
import tw.com.ispan.gym.entity.course.PrivateCourse;

import tw.com.ispan.gym.enums.course.CourseStatus;

public record PrivateCourseResponse(
        Integer id,
        String name,
        Integer maxCapacity,
        Integer sessionPerUnit,
        BigDecimal price,
        String description,
        String imageUrl,
        Boolean isTrial,
        CourseStatus status,
        TrainerDto trainer,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PrivateCourseResponse from(PrivateCourse course) {
        if (course == null) {
            return null;
        }

        return new PrivateCourseResponse(
                course.getId(),
                course.getName(),
                course.getMaxCapacity(),
                course.getSessionPerUnit(),
                course.getPrice(),
                course.getDescription(),
                course.getImageUrl(),
                course.getIsTrial(),
                course.getStatus(),
                TrainerDto.from(course.getTrainer()),
                course.getCreatedAt(),
                course.getUpdatedAt()
        );
    }
}
