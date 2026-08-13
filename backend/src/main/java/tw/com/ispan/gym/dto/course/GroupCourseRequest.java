package tw.com.ispan.gym.dto.course;

import lombok.Data;
import tw.com.ispan.gym.enums.CourseStatus;

@Data
public class GroupCourseRequest {
    private String name;
    private String description;
    private String imageUrl;
    private Boolean isTrial;
    private Boolean isOpenForSubscription;
    private CourseStatus status;
    private Integer maxCapacity;
    private Integer courseCategoryId;
}
