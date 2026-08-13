package tw.com.ispan.gym.dto.course;

import lombok.Data;
import tw.com.ispan.gym.enums.CourseStatus;

@Data
public class GroupCourseRequest {
    private String name;
    /** 授課教練 */
    private Integer trainerId;
    private String description;
    private String imageUrl;
    private Boolean isTrial;
    private CourseStatus status;
    private Integer maxCapacity;
    private Integer courseCategoryId;
}
