package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;
import tw.com.ispan.gym.enums.coach.SlotStatus;

/**
 * 管理端查詢「指定教練」排課情況用，跟 {@link CoachSlotResponse} 分開是刻意的：
 * 那支只給教練自助端用（建立/修改/查自己的時段），這裡多帶 memberId／memberName
 * 供管理員唯讀查看時段被誰預約，不想為了多兩個欄位去動教練自助端既有的正常流程。
 */
@Data
@AllArgsConstructor
public class AdminCoachSlotResponse {
    private Integer id;
    private Integer trainerId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private SlotStatus status;
    /** 這個時段目前非取消狀態的預約 id；沒有預約時為 null */
    private Integer bookingId;
    /** 對應 bookingId 那筆預約的狀態；規則同 bookingId */
    private CoachBookingStatus bookingStatus;
    /** 預約會員 id；沒有預約時為 null */
    private Integer memberId;
    /** 預約會員姓名（lastName + firstName）；沒有預約時為 null */
    private String memberName;
}
