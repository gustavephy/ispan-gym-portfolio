package tw.com.ispan.gym.dto.course;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import tw.com.ispan.gym.dto.trainer.TrainerDto;
import tw.com.ispan.gym.entity.course.PrivateCourse;

import tw.com.ispan.gym.enums.course.CourseStatus;

public record PrivateCourseResponse(
        Integer privateCourseId,
        String name,
        Integer maxCapacity,
        Integer sessionPerUnit,
        BigDecimal price,
        String description,
        String imageUrl,
        Boolean isTrial,
        CourseStatus status,
        // 前台課程方案頁的分類篩選靠這兩個欄位；category 在 DB 允許為 null，前端需自行容錯
        Integer courseCategoryId,
        String courseCategoryName,
        TrainerDto trainer,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static PrivateCourseResponse from(PrivateCourse course) {
        if (course == null) {
            return null;
        }

        var category = course.getCategory();
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
                category != null ? category.getCourseCategoryId() : null,
                category != null ? category.getName() : null,
                TrainerDto.from(course.getTrainer()),
                course.getCreatedAt(),
                course.getUpdatedAt());
    }
}
