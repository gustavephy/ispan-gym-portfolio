package tw.com.ispan.gym.dto.course;

import java.math.BigDecimal;
import tw.com.ispan.gym.enums.course.CourseStatus;

public record PrivateCourseRequest(
        String name,
        BigDecimal price,
        String description,
        String imageUrl,
        Boolean isTrial,
        CourseStatus status,
        Integer trainerId,
        Integer maxCapacity,
        Integer sessionPerUnit,
        Integer courseCategoryId
) {}
