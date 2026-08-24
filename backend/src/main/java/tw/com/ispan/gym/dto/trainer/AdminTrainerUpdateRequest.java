package tw.com.ispan.gym.dto.trainer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * ADMIN 修改 Trainer 專業資料用（PUT /api/admin/trainers/{trainerId}）。欄位跟
 * TrainerProfileUpdateRequest（教練本人）相同，分開建類別是比照 Employee 既有風格
 * （AdminEmployeeUpdateRequest／EmployeeProfileUpdateRequest 也是各自獨立）。
 * Employee 姓名／電話等一般個資改走 AdminEmployeeUpdateRequest，不在這裡處理。
 */
@Getter
@Setter
public class AdminTrainerUpdateRequest {

    @NotBlank(message = "專長為必填")
    @Size(max = 255, message = "專長不可超過255個字元")
    private String specialty;

    @NotBlank(message = "經歷為必填")
    @Size(max = 1000, message = "經歷不可超過1000個字元")
    private String experience;

    @NotBlank(message = "自我介紹為必填")
    @Size(max = 5000, message = "自我介紹不可超過5000個字元")
    private String bio;

    @Size(max = 255, message = "個人圖片網址不可超過255個字元")
    private String profileImageUrl;
}
