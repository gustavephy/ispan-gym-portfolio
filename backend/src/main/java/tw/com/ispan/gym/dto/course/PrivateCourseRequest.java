package tw.com.ispan.gym.dto.course;

import java.math.BigDecimal;

import lombok.Data;
import tw.com.ispan.gym.enums.CourseStatus;

@Data
public class PrivateCourseRequest {
    private String name;
    private BigDecimal price;
    private String description;
    private String imageUrl;
    private Boolean isTrial;
    private CourseStatus status;
    private Integer trainerId;
    private Integer maxCapacity;
    private Integer sessionPerUnit;
    private Integer courseCategoryId;
}
