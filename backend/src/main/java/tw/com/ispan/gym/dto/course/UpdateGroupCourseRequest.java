package tw.com.ispan.gym.dto.course;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 編輯團課主檔時可更新的欄位。此 DTO 用於後台管理介面的 PUT /api/employee/group-courses/{id}，
 * 跟建立用的 {@link GroupCourseRequest} 分開：不含 trainerId（授課教練不可透過修改變更，
 * 換教練需另開課程）、不含 status（狀態只能透過既有的下架端點改變，避免兩條路徑都能改狀態）。
 *
 * description、imageUrl、isTrial、courseCategoryId 允許為 null，代表「維持原值」，
 * 不是「清空」或「重設成預設值」；name、maxCapacity 為必填，全量覆蓋。
 */
@Data
public class UpdateGroupCourseRequest {
    @NotBlank(message = "課程名稱不可為空")
    private String name;

    @NotNull(message = "人數上限不可為空")
    @Min(value = 1, message = "人數上限至少為 1")
    private Integer maxCapacity;

    private String description;
    private String imageUrl;
    private Boolean isTrial;
    private Integer courseCategoryId;
}
