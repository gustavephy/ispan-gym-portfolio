package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 管理員查詢私教課堂數異動稽核紀錄時的回應資料。
 *
 * <p>
 * 每一筆對應一次 BOOK/CHECKIN/CANCEL_REFUND/CANCEL_CONSUME/CREDIT 事件，記錄異動前後的
 * remaining/reserved/used 快照，用於審計核銷爭議與報表統計，見
 * {@link tw.com.ispan.gym.entity.coach.SessionDeductionLog} 的說明。
 */
@Data
@AllArgsConstructor
public class SessionDeductionLogResponse {
    private Integer id;
    private String eventType;
    private Integer delta;
    private Integer beforeRemaining;
    private Integer afterRemaining;
    private Integer beforeReserved;
    private Integer afterReserved;
    private Integer beforeUsed;
    private Integer afterUsed;
    private String remark;
    /** 執行核銷的教練顯示名；會員自助訂課／取消與系統發放額度時沒有操作教練，這裡是 null */
    private String operatorTrainerName;
    /** 課程名稱，是購買當下的名稱快照，不是即時查課程主檔 */
    private String courseName;
    private LocalDateTime createdAt;
}
