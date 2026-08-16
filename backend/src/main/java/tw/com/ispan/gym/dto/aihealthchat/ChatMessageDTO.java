package tw.com.ispan.gym.dto.aihealthchat;

import java.time.LocalDateTime;

/**
 * 對外的訊息格式，把 entity 的 sender_is_member／counterpart_type 轉成前端好懂的欄位。
 *
 * <p>counterpartLabel 直接給前端當分頁籤文字用（"AI顧問" 或 "教練"），對應聊天室
 * 只分兩種切換分頁的設計；senderIsMember／counterpartType 仍保留原始值，供需要精確
 * 判斷（而非僅顯示文字）的邏輯使用。
 *
 * <p>mine 是「相對於目前檢視者」的欄位（該則訊息是不是檢視者自己傳的），因檢視者
 * 身分（學員／員工）而異，只在 REST 回應（getHistory）計算；WebSocket 房間廣播是
 * 兩種身分共用同一個頻道，broadcast 版本不帶 mine，由前端自行比對 senderIsMember
 * 與自己的身分算出來即可，不需要伺服器針對同一則訊息組兩種不同 payload 各推一次。
 */
public record ChatMessageDTO(
        Integer id,
        String roomId,
        String counterpartType,
        String counterpartLabel,
        Integer employeeId,
        boolean senderIsMember,
        Boolean mine,
        String content,
        boolean read,
        LocalDateTime readAt,
        LocalDateTime createdAt) {
}
