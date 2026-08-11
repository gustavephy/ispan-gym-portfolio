package tw.com.ispan.gym.dto.course;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;
import tw.com.ispan.gym.enums.CourseStatus;
import tw.com.ispan.gym.enums.CourseType;

@Data
public class CourseResponse {
    private Integer id;
    private TrainerDto trainer;
    private String name;
    private Integer maxCapacity;
    private Integer sessionsPerUnit;
    private BigDecimal price;
    private String description;
    private String imageUrl;
    private Boolean isTrial;
    private Boolean isOpenForSubscription;
    private CourseType courseType;
    private CourseStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}