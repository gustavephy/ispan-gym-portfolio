package tw.com.ispan.gym.dto.course;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.course.CourseStatus;

@Data
@AllArgsConstructor
public class GroupCourseResponse {
    private Integer id;
    private String name;
    private Integer trainerId;
    private String trainerName;
    private String description;
    private String imageUrl;
    private Integer maxCapacity;
    private Boolean isTrial;
    private CourseStatus status;
    /** 課程分類 id，courseCategoryId 為 null 時代表未分類 */
    private Integer courseCategoryId;
    /** 對應 courseCategoryId 的分類名稱，courseCategoryId 為 null 時一併為 null */
    private String courseCategoryName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
