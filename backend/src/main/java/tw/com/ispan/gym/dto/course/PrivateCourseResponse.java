package tw.com.ispan.gym.dto.course;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import tw.com.ispan.gym.dto.trainer.TrainerDto;
import tw.com.ispan.gym.entity.course.PrivateCourse;

import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.enums.course.CourseType;

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
        CourseType courseType,
        TrainerDto trainer,
        Integer courseCategoryId,
        String courseCategoryName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PrivateCourseResponse from(PrivateCourse course) {
        if (course == null) {
            return null;
        }

        return new PrivateCourseResponse(
                course.getPrivateCourseId(),
                course.getName(),
                course.getMaxCapacity(),
                course.getSessionPerUnit(),
                course.getPrice(),
                course.getDescription(),
                course.getImageUrl(),
                course.getIsTrial(),
                course.getStatus(),
                CourseType.PRIVATE,
                TrainerDto.from(course.getTrainer()),
                course.getCategory() != null ? course.getCategory().getCourseCategoryId() : null,
                course.getCategory() != null ? course.getCategory().getName() : null,
                course.getCreatedAt(),
                course.getUpdatedAt()
        );
    }
}
