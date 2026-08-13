package tw.com.ispan.gym.dto.course;

import lombok.Data;
import tw.com.ispan.gym.enums.CourseStatus;

@Data
public class GroupCourseRequest {
    private String name;
    /** 預設授課教練；實際授課者以場次為準 */
    private Integer trainerId;
    private String description;
    private String imageUrl;
    private Boolean isTrial;
    private Boolean isOpenForSubscription;
    private CourseStatus status;
    private Integer maxCapacity;
    private Integer courseCategoryId;
}
