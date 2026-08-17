package tw.com.ispan.gym.dto.course;

import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.dto.trainer.TrainerDto;

public record GroupCourseResponse(
        Integer groupCourseId,
        String name,
        Integer maxCapacity,
        String description,
        String imageUrl,
        Boolean isTrial,
        CourseStatus status,
        CourseCategoryDto category,
        TrainerDto trainer,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static GroupCourseResponse from(GroupCourse groupCourse) {
        if (groupCourse == null) {
            return null;
        }

        CourseCategoryDto categoryDto = groupCourse.getCategory() != null
                ? CourseCategoryDto.from(groupCourse.getCategory())
                : null;

        return new GroupCourseResponse(
                groupCourse.getId(),
                groupCourse.getName(),
                groupCourse.getMaxCapacity(),
                groupCourse.getDescription(),
                groupCourse.getImageUrl(),
                groupCourse.getIsTrial(),
                groupCourse.getStatus(),
                categoryDto,
                TrainerDto.from(groupCourse.getTrainer()),
                groupCourse.getCreatedAt(),
                groupCourse.getUpdatedAt());
    }
}
