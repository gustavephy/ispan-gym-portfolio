package tw.com.ispan.gym.dto.course;

import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.enums.course.CourseType;
import tw.com.ispan.gym.dto.trainer.TrainerDto;

public record GroupCourseResponse(
        Integer groupCourseId,
        String name,
        Integer maxCapacity,
        String description,
        String imageUrl,
        Boolean isTrial,
        CourseStatus status,
        CourseType courseType,
        CourseCategoryDto category,
        TrainerDto trainer,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static GroupCourseResponse from(GroupCourse groupCourse) {
        if (groupCourse == null) {
            return null;
        }

        // 預防課程未設定分類時，呼叫 from() 產生空指標異常 (NPE)
        CourseCategoryDto categoryDto = groupCourse.getCategory() != null
                ? CourseCategoryDto.from(groupCourse.getCategory())
                : null;

        return new GroupCourseResponse(
                groupCourse.getGroupCourseId(),
                groupCourse.getName(),
                groupCourse.getMaxCapacity(),
                groupCourse.getDescription(),
                groupCourse.getImageUrl(),
                groupCourse.getIsTrial(),
                groupCourse.getStatus(),
                CourseType.GROUP,
                categoryDto,
                TrainerDto.from(groupCourse.getTrainer()),
                groupCourse.getCreatedAt(),
                groupCourse.getUpdatedAt());
    }
}
