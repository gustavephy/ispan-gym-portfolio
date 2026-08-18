package tw.com.ispan.gym.dto.course;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import tw.com.ispan.gym.enums.course.CourseStatus;

@Data
public class GroupCourseRequest {
    @NotBlank
    private String name;

    /** 授課教練 */
    @NotNull
    private Integer trainerId;

    private String description;
    private String imageUrl;

    /** 允許不填；Service 層預設視為 false，不放在這裡用 initializer（JSON 反序列化不保證生效） */
    private Boolean isTrial;

    /** 允許不填；Service 層預設視為 ACTIVE，理由同 isTrial */
    private CourseStatus status;

    @NotNull
    @Min(1)
    private Integer maxCapacity;

    private Integer courseCategoryId;
}
