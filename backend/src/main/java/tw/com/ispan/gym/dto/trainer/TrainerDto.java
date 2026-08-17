package tw.com.ispan.gym.dto.trainer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 教練資訊 DTO
 * 
 * 用於前台「教練團隊介紹」、課程細節中的教練資訊展示，以及後台教練管理列表。
 * 僅對外暴露必要欄位，不包含 pwdHash 等敏感資訊。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrainerDto {
    private Integer id;
    private String name; // lastName + firstName 組合
    private String email;
    private String specialty; // 教練專長（來自 trainer_details，可為 null）
    private String bio; // 個人簡介
    private String experience; // 年資經歷
    private String avatarUrl; // 教練頭像 URL
}
