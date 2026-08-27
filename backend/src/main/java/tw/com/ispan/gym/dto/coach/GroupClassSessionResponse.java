package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.coach.ClassRoom;
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

    /** 教室代碼（enum 本身），後台編輯表單回填 &lt;select&gt; 用。 */
    private ClassRoom roomCode;
    /** 教室中文顯示名稱，由 roomCode.getDisplayName() 產生，純顯示用途維持不變。 */
    private String roomName;
    /** 教室代表色（hex），取代前端原本自己維護的顏色對照表。 */
    private String roomColor;
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

    /**
     * 手動覆寫 isFull 的 getter（@Data 偵測到同名方法已存在，就不會再自動產生一份）。
     * 原因：Lombok 對 isFull 欄位產生的 getter 名稱就是 isFull()，
     * 但 Jackson 對 isXxx() 這種命名的 getter，預設會把開頭的 is 去掉，
     * 自動序列化成 "full" 而非 "isFull"，跟這支 API 文件寫的欄位名稱兜不起來，
     * 前端會拿不到值、一直誤判成 false。
     * 若只在欄位上加 @JsonProperty("isFull")，Jackson 會把「欄位」與「getter」
     * 視為兩個不同屬性（因為兩者推導出的名稱不同），變成同時輸出 "full" 和 "isFull"
     * 兩個 key，一樣是錯的；必須直接把 annotation 標在 getter 本身，
     * 才能蓋掉 Jackson 對這個 getter 自動推導出的屬性名稱。
     */
    @JsonProperty("isFull")
    public boolean isFull() {
        return isFull;
    }
}