package tw.com.ispan.gym.dto.trainer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 不接受 status——建立時一律由後端固定為 ACTIVE，避免呼叫端任意指定狀態 */
@Getter
@Setter
public class TrainerCreateRequest {

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
