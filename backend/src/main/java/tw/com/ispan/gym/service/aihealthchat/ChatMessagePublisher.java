package tw.com.ispan.gym.service.aihealthchat;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import tw.com.ispan.gym.dto.aihealthchat.ChatMessageDTO;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage.ChatCounterpartType;
import tw.com.ispan.gym.repository.aihealthchat.ChatMessageRepository;

/**
 * 「寫入 DB＋推送 WebSocket」這個共用步驟獨立成單一元件，讓 {@link ChatService}
 * （學員／員工發送訊息）與 {@link ChatAiResponderService}（AI 非同步回覆／課程推薦）
 * 都能呼叫同一套邏輯，不論訊息來源一律共用同一套儲存與推播（規格書 §5.3）。
 *
 * <p>獨立出來還有一個更關鍵的原因：{@link ChatAiResponderService} 的方法標了
 * {@code @Async}，若讓它們透過 {@code this.xxx()} 呼叫同一個類別內的方法，
 * Spring AOP 代理會被繞過、@Async 直接失效變成同步執行——拆成不同 bean、
 * 一律透過注入的物件呼叫，才能確保真的有非同步效果。
 */
@Component
public class ChatMessagePublisher {
    private final ChatMessageRepository chatMessages;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatMessagePublisher(ChatMessageRepository chatMessages, SimpMessagingTemplate messagingTemplate) {
        this.chatMessages = chatMessages;
        this.messagingTemplate = messagingTemplate;
    }

    public ChatMessage saveAndPush(ChatMessage message) {
        ChatMessage saved = chatMessages.save(message);
        // 廣播版本不帶 mine（該欄位是相對於檢視者的概念，見 ChatMessageDTO 註解）
        messagingTemplate.convertAndSend("/topic/room/" + saved.getRoomId(), toDto(saved, null));
        return saved;
    }

    public ChatMessageDTO toDto(ChatMessage m, Boolean viewerIsMember) {
        String label = m.getCounterpartType() == ChatCounterpartType.AI ? "AI顧問" : "教練";
        Boolean mine = viewerIsMember == null ? null : viewerIsMember.equals(m.isSenderIsMember());
        return new ChatMessageDTO(m.getId(), m.getRoomId(), m.getCounterpartType().name(), label,
                m.getEmployeeId(), m.isSenderIsMember(), mine, m.getMessageContent(),
                m.isRead(), m.getReadAt(), m.getCreatedAt());
    }
}
