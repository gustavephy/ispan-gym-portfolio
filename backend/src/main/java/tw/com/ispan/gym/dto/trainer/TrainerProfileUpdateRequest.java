package tw.com.ispan.gym.dto.trainer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 教練本人修改專業資料用（PUT /api/trainer/profile）。只能改 specialty／experience／bio／
 * profileImageUrl——不接受 status／deletedAt／employeeId／trainerId，也不接受一般個資
 * （改走 EmployeeProfileUpdateRequest）或 Email／密碼（改走帳號安全流程）。
 */
@Getter
@Setter
public class TrainerProfileUpdateRequest {

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
