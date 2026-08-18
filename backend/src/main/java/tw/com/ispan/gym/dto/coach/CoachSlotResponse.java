package tw.com.ispan.gym.dto.coach;


import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;
import tw.com.ispan.gym.enums.coach.SlotStatus;

@Data
@AllArgsConstructor
public class CoachSlotResponse  {
    private Integer id;
    private Integer trainerId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private SlotStatus status;
    /** 這個時段目前非取消狀態的預約 id；沒有預約或查詢情境不需暴露（如 searchSlots）時為 null */
    private Integer bookingId;
    /** 對應 bookingId 那筆預約的狀態；規則同 bookingId */
    private CoachBookingStatus bookingStatus;
}
