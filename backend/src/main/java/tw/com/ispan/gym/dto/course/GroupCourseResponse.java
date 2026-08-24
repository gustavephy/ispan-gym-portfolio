package tw.com.ispan.gym.dto.course;

import java.time.LocalDateTime;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;

public record GroupCourseResponse(
        Integer id,
        String name,
        Integer trainerId,
        String trainerName,
        String description,
        String imageUrl,
        Integer maxCapacity,
        Boolean isTrial,
        CourseStatus status,
        Integer courseCategoryId,
        String courseCategoryName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static GroupCourseResponse from(GroupCourse groupCourse) {
        if (groupCourse == null) {
            return null;
        }

        Trainer trainer = groupCourse.getTrainer();
        CourseCategory category = groupCourse.getCategory();

        return new GroupCourseResponse(
                groupCourse.getGroupCourseId(),
                groupCourse.getName(),
                trainer != null ? trainer.getTrainerId() : null,
                trainer != null && trainer.getEmployee() != null 
                        ? trainer.getEmployee().getFirstName() + trainer.getEmployee().getLastName() 
                        : null,
                groupCourse.getDescription(),
                groupCourse.getImageUrl(),
                groupCourse.getMaxCapacity(),
                groupCourse.getIsTrial(),
                groupCourse.getStatus(),
                category != null ? category.getCourseCategoryId() : null,
                category != null ? category.getName() : null,
                groupCourse.getCreatedAt(),
                groupCourse.getUpdatedAt()
        );
    }
}
