package tw.com.ispan.gym.dto.aihealthchat;

import java.time.LocalDateTime;

/**
 * 教練「學員訊息」列表的單筆摘要：對話對象是哪位學員、最後一則訊息內容／時間、
 * 該學員底下還沒讀的訊息數，供教練一眼看出誰傳了新訊息。
 */
public record ChatConversationSummary(
        Integer memberId,
        String memberName,
        String lastMessage,
        LocalDateTime lastMessageAt,
        long unreadCount) {
}
