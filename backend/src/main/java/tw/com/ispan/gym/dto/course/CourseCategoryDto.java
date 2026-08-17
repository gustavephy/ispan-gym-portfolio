package tw.com.ispan.gym.dto.course;

import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.enums.common.CategoryStatus;

public record CourseCategoryDto(
        Integer courseCategoryId,
        String name,
        Integer sortOrder,
        CategoryStatus status
) {
    public static CourseCategoryDto from(CourseCategory courseCategory) {
        if (courseCategory == null) {
            return null;
        }
        return new CourseCategoryDto(
                courseCategory.getCourseCategoryId(),
                courseCategory.getName(),
                courseCategory.getSortOrder(),
                courseCategory.getStatus() != null ? courseCategory.getStatus() : CategoryStatus.ACTIVE
        );
    }
}
