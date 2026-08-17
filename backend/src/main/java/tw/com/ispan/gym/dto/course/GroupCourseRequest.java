package tw.com.ispan.gym.dto.course;

import tw.com.ispan.gym.enums.course.CourseStatus;

public record GroupCourseRequest(
        String name,
        Integer trainerId,
        String description,
        String imageUrl,
        Boolean isTrial,
        CourseStatus status,
        Integer maxCapacity,
        Integer courseCategoryId
) {}
