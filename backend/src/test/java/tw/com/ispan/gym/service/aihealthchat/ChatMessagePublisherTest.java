package tw.com.ispan.gym.service.aihealthchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import tw.com.ispan.gym.dto.aihealthchat.ChatMessageDTO;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage.ChatCounterpartType;
import tw.com.ispan.gym.repository.aihealthchat.ChatMessageRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 這個元件只有兩件事但都容易出錯：先落地再推播的順序（顛倒的話推出去的訊息可能沒有 id，
 * 且 DB 寫入失敗時已經廣播出去收不回來），以及 mine 欄位的相對性——
 * 同一則訊息對學員和對教練的 mine 值相反，廣播版本則必須是 null（廣播沒有特定檢視者）。
 */
class ChatMessagePublisherTest {

    private static final Integer MEMBER_ID = 10;
    private static final Integer EMPLOYEE_ID = 20;

    private final ChatMessageRepository chatMessages = mock(ChatMessageRepository.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final ChatMessagePublisher publisher = new ChatMessagePublisher(chatMessages, messagingTemplate);

    // === 共用測試資料 ========================================================

    private ChatMessage message(ChatCounterpartType type, boolean senderIsMember, String roomId) {
        ChatMessage message = ChatMessage.builder()
                .memberId(MEMBER_ID)
                .counterpartType(type)
                .employeeId(type == ChatCounterpartType.EMPLOYEE ? EMPLOYEE_ID : null)
                .senderIsMember(senderIsMember)
                .messageContent("測試訊息")
                .build();
        // roomId 正式流程由 @PrePersist 產生，單元測試沒有 JPA 生命週期，手動補上
        ReflectionTestUtils.setField(message, "roomId", roomId);
        ReflectionTestUtils.setField(message, "id", 99);
        ReflectionTestUtils.setField(message, "createdAt", LocalDateTime.now());
        return message;
    }

    private void stubSaveEchoes() {
        when(chatMessages.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // === saveAndPush() =======================================================

    @Test
    void 先寫入資料庫再推播() {
        stubSaveEchoes();
        ChatMessage message = message(ChatCounterpartType.AI, true, "member_10_ai");

        publisher.saveAndPush(message);

        // 順序反了會推播出沒有 id 的訊息，且 DB 失敗時廣播已經收不回來
        InOrder order = inOrder(chatMessages, messagingTemplate);
        order.verify(chatMessages).save(message);
        order.verify(messagingTemplate).convertAndSend(eq("/topic/room/member_10_ai"), any(ChatMessageDTO.class));
    }

    @Test
    void 推播到訊息本身的房間頻道() {
        stubSaveEchoes();

        publisher.saveAndPush(message(ChatCounterpartType.EMPLOYEE, false, "member_10_employee_20"));

        messagingTemplateReceived("/topic/room/member_10_employee_20");
    }

    @Test
    void 廣播版本不帶mine因為廣播沒有特定檢視者() {
        stubSaveEchoes();

        publisher.saveAndPush(message(ChatCounterpartType.AI, true, "member_10_ai"));

        org.mockito.ArgumentCaptor<ChatMessageDTO> captor =
                org.mockito.ArgumentCaptor.forClass(ChatMessageDTO.class);
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(eq("/topic/room/member_10_ai"), captor.capture());
        assertThat(captor.getValue().mine()).isNull();
    }

    @Test
    void 回傳存檔後的訊息供呼叫端接續使用() {
        stubSaveEchoes();
        ChatMessage message = message(ChatCounterpartType.AI, true, "member_10_ai");

        ChatMessage saved = publisher.saveAndPush(message);

        assertThat(saved.getRoomId()).isEqualTo("member_10_ai");
        assertThat(saved.getId()).isEqualTo(99);
    }

    private void messagingTemplateReceived(String destination) {
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(eq(destination), any(ChatMessageDTO.class));
    }

    // === toDto()：mine 的相對性 ================================================

    @Test
    void 學員檢視自己傳的訊息mine為真() {
        ChatMessageDTO dto = publisher.toDto(message(ChatCounterpartType.AI, true, "member_10_ai"), true);

        assertThat(dto.mine()).isTrue();
    }

    @Test
    void 學員檢視AI傳的訊息mine為假() {
        ChatMessageDTO dto = publisher.toDto(message(ChatCounterpartType.AI, false, "member_10_ai"), true);

        assertThat(dto.mine()).isFalse();
    }

    @Test
    void 同一則學員訊息在教練視角mine為假() {
        ChatMessage fromMember = message(ChatCounterpartType.EMPLOYEE, true, "member_10_employee_20");

        // 同一則訊息，兩種視角的 mine 相反
        assertThat(publisher.toDto(fromMember, true).mine()).isTrue();
        assertThat(publisher.toDto(fromMember, false).mine()).isFalse();
    }

    @Test
    void 未指定檢視者時mine為null() {
        ChatMessageDTO dto = publisher.toDto(message(ChatCounterpartType.AI, true, "member_10_ai"), null);

        assertThat(dto.mine()).isNull();
    }

    // === toDto()：顯示標籤 =====================================================

    @Test
    void AI對話標籤為AI顧問教練對話標籤為教練() {
        assertThat(publisher.toDto(message(ChatCounterpartType.AI, true, "member_10_ai"), true)
                .counterpartLabel()).isEqualTo("AI顧問");
        assertThat(publisher.toDto(message(ChatCounterpartType.EMPLOYEE, true, "member_10_employee_20"), true)
                .counterpartLabel()).isEqualTo("教練");
    }

    @Test
    void DTO帶出房間與員工id供前端分流() {
        ChatMessageDTO dto = publisher.toDto(
                message(ChatCounterpartType.EMPLOYEE, true, "member_10_employee_20"), true);

        assertThat(dto.roomId()).isEqualTo("member_10_employee_20");
        assertThat(dto.employeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(dto.counterpartType()).isEqualTo("EMPLOYEE");
    }
}
