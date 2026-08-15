package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;

@Data
@AllArgsConstructor
/**
 * DTO 返回物件：團課場次資訊（供前端顯示使用）。
 *
 * 注意：此 DTO 包含管理/顯示所需的欄位（例如教練姓名、版本號、建立/更新時間），
 * 前端應妥善處理時間格式與版本回送以支援樂觀鎖。
 */
public class GroupClassSessionResponse {
    private Integer id;

    /**
     * 對應的團課主檔 id，可用於前端導回課程細節頁或建立連結。
     */
    private Integer groupCourseId; // 方便前端連回課程

    /**
     * 授課教練資訊（id + 顯示名稱）。教練綁在 GroupCourse 上，場次不另外存教練。
     *
     * 命名使用專案慣例 `trainerId` / `trainerName`（統一與其他模組命名一致）。
     */
    private Integer trainerId; // 授課教練 id
    private String trainerName; // 授課教練顯示名

    private String courseName;
    private String roomName;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer capacity;

    /** 已報名人數（可用於顯示 "已報名 X 人"） */
    private Integer bookedCount; // 已報名數

    /** 由 entity 計算：capacity - bookedCount */
    private Integer remainingSeats;

    /** 由後端計算的快速布林，前端可直接用來停用報名按鈕 */
    private boolean isFull; // 快速標示是否額滿

    /** 場次狀態（SCHEDULED / CANCELLED 等） */
    private GroupClassSessionStatus status; // 場次狀態

    /**
     * JPA 樂觀鎖版本（@Version），前端在執行修改時應帶回以避免覆蓋衝突。
     */
    private Integer version; // 樂觀鎖版本

    /** 建立/更新時間。注意 LocalDateTime 無時區，請在前端約定解析方式。 */
    private LocalDateTime createdAt; // 建立時間（台北時區，由 entity 填）
    private LocalDateTime updatedAt; // 更新時間
}