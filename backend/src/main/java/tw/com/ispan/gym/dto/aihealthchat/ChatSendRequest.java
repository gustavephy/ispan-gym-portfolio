package tw.com.ispan.gym.dto.aihealthchat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage.ChatCounterpartType;

/**
 * 學員發起／回覆訊息的請求格式，對應 STOMP 目的地 /app/chat.send。
 * employeeId 僅在 counterpartType=EMPLOYEE 時需要（切到"教練"分頁時由前端帶入
 * 該教練的 employeeId），counterpartType=AI（"AI顧問"分頁）時必須留空。
 */
public record ChatSendRequest(
        @NotBlank @Size(max = 500) String content,
        @NotNull ChatCounterpartType counterpartType,
        Integer employeeId) {
}
