package tw.com.ispan.gym.service.aihealthchat;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.aihealthchat.ChatConversationSummary;
import tw.com.ispan.gym.dto.aihealthchat.ChatMessageDTO;
import tw.com.ispan.gym.dto.aihealthchat.ChatReplyRequest;
import tw.com.ispan.gym.dto.aihealthchat.ChatSendRequest;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage.ChatCounterpartType;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.aihealthchat.ChatMessageRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 聊天室（2.4）核心邏輯。對外分兩種入口：
 * <ul>
 * <li>WebSocket（ChatWebSocketController）：呼叫端只有 STOMP 連線驗證出來的
 * account_id，本類別的 send/reply 方法自行反查並驗證會員／員工身分。</li>
 * <li>REST（ChatController）：呼叫端已透過 CurrentUserProvider 或既有慣例
 * （accountId → service 內反查）取得身分，直接查歷史／已讀／未讀數／觸發課程推薦。</li>
 * </ul>
 * 不論訊息來自學員、員工或 AI，一律先寫入 DB 再推送到房間頻道，統一交給
 * {@link ChatMessagePublisher}；真正呼叫 Gemini 的部分（一般回覆／課程推薦）
 * 交給 {@link ChatAiResponderService} 非同步執行，本類別只負責觸發、不等待結果。
 */
@Service
public class ChatService {
    private final ChatMessageRepository chatMessages;
    private final MemberRepository members;
    private final EmployeeRepository employees;
    private final ChatMessagePublisher publisher;
    private final ChatAiResponderService aiResponder;

    public ChatService(ChatMessageRepository chatMessages, MemberRepository members,
            EmployeeRepository employees, ChatMessagePublisher publisher, ChatAiResponderService aiResponder) {
        this.chatMessages = chatMessages;
        this.members = members;
        this.employees = employees;
        this.publisher = publisher;
        this.aiResponder = aiResponder;
    }

    // ───────────────────────── WebSocket 入口 ─────────────────────────

    /** 學員發起／回覆訊息，對象為 AI顧問 或 教練（由 request.counterpartType 決定） */
    public void sendFromMember(Integer accountId, ChatSendRequest request) {
        Integer memberId = resolveActiveMemberId(accountId);
        ChatMessage saved = publisher.saveAndPush(buildMemberMessage(memberId, request));
        if (saved.getCounterpartType() == ChatCounterpartType.AI) {
            aiResponder.generateChatReplyAsync(memberId, saved.getRoomId());
        }
    }

    /** 員工（教練／管理員）回覆指定學員的訊息 */
    public void replyFromEmployee(Integer accountId, ChatReplyRequest request) {
        Integer employeeId = resolveActiveEmployeeId(accountId);
        requireActiveMember(request.memberId());

        ChatMessage message = ChatMessage.builder()
                .memberId(request.memberId())
                .counterpartType(ChatCounterpartType.EMPLOYEE)
                .employeeId(employeeId)
                .senderIsMember(false)
                .messageContent(request.content())
                .build();
        publisher.saveAndPush(message);
    }

    // ───────────────────────── REST：學員端 ─────────────────────────

    public Page<ChatMessageDTO> getHistoryForMember(Integer memberId, ChatCounterpartType type,
            Integer employeeId, int page, int size) {
        String roomId = roomIdForMember(memberId, type, employeeId);
        return chatMessages.findByRoomIdOrderByCreatedAtDesc(roomId, PageRequest.of(page, size))
                .map(m -> publisher.toDto(m, true));
    }

    /**
     * markRoomAsRead 是自訂的 @Modifying JPQL bulk update，Spring Data 不會像
     * save()／delete() 那樣自動幫這類自訂查詢包交易，呼叫端沒有 @Transactional
     * 會直接拋 TransactionRequiredException（500）。
     */
    @Transactional
    public void markReadForMember(Integer memberId, ChatCounterpartType type, Integer employeeId) {
        String roomId = roomIdForMember(memberId, type, employeeId);
        // bySenderIsMember=false：把「對方（AI／教練）傳的」訊息標成已讀，不是自己傳的那些
        chatMessages.markRoomAsRead(roomId, false);
    }

    public long unreadCountForMember(Integer memberId) {
        return chatMessages.countByMemberIdAndSenderIsMemberFalseAndReadFalse(memberId);
    }

    /**
     * 學員在「AI顧問」分頁主動按「推薦課程」時觸發，屬於獨立於一般聊天訊息之外的動作
     * （規格書 §9.2 定案：不是每次聊天都自動查課程表，避免浪費 token、干擾一般對話）。
     * 立刻回傳、非同步生成，結果會以一則 AI 訊息推播回 member_{id}_ai 房間。
     */
    public void requestCourseRecommendation(Integer memberId) {
        aiResponder.generateCourseRecommendationAsync(memberId, "member_" + memberId + "_ai");
    }

    // ───────────────────────── REST：員工端 ─────────────────────────

    public Page<ChatMessageDTO> getHistoryForEmployeeAccount(Integer accountId, Integer memberId, int page,
            int size) {
        Integer employeeId = resolveActiveEmployeeId(accountId);
        String roomId = roomIdForEmployee(employeeId, memberId);
        return chatMessages.findByRoomIdOrderByCreatedAtDesc(roomId, PageRequest.of(page, size))
                .map(m -> publisher.toDto(m, false));
    }

    @Transactional
    public void markReadForEmployeeAccount(Integer accountId, Integer memberId) {
        Integer employeeId = resolveActiveEmployeeId(accountId);
        String roomId = roomIdForEmployee(employeeId, memberId);
        // bySenderIsMember=true：把「學員傳的」訊息標成已讀
        chatMessages.markRoomAsRead(roomId, true);
    }

    public long unreadCountForEmployeeAccount(Integer accountId) {
        Integer employeeId = resolveActiveEmployeeId(accountId);
        return chatMessages.countByEmployeeIdAndSenderIsMemberTrueAndReadFalse(employeeId);
    }

    /**
     * 教練「學員訊息」列表：列出所有曾與這位員工對話過的學員，依最後訊息時間排序，
     * 附最後一則訊息預覽與未讀數，取代教練得先知道 memberId 才能查歷史的窘境
     * （getHistoryForEmployeeAccount 需要呼叫端已經知道要查哪位學員）。
     */
    public List<ChatConversationSummary> listConversationsForEmployeeAccount(Integer accountId) {
        Integer employeeId = resolveActiveEmployeeId(accountId);
        return chatMessages.findMemberIdsByEmployeeIdOrderByLastMessageDesc(employeeId).stream()
                .map(memberId -> buildConversationSummary(employeeId, memberId))
                .toList();
    }

    private ChatConversationSummary buildConversationSummary(Integer employeeId, Integer memberId) {
        String roomId = roomIdForEmployee(employeeId, memberId);
        ChatMessage last = chatMessages.findByRoomIdOrderByCreatedAtDesc(roomId, PageRequest.of(0, 1))
                .getContent().stream().findFirst().orElse(null);
        String memberName = members.findById(memberId)
                .map(m -> m.getLastName() + m.getFirstName())
                .orElse("會員 #" + memberId);
        long unread = chatMessages.countByMemberIdAndEmployeeIdAndSenderIsMemberTrueAndReadFalse(memberId, employeeId);
        return new ChatConversationSummary(memberId, memberName,
                last != null ? last.getMessageContent() : null,
                last != null ? last.getCreatedAt() : null,
                unread);
    }

    // ───────────────────────── 私有輔助 ─────────────────────────

    private ChatMessage buildMemberMessage(Integer memberId, ChatSendRequest request) {
        ChatMessage.ChatMessageBuilder builder = ChatMessage.builder()
                .memberId(memberId)
                .counterpartType(request.counterpartType())
                .senderIsMember(true)
                .messageContent(request.content());

        if (request.counterpartType() == ChatCounterpartType.EMPLOYEE) {
            Integer employeeId = request.employeeId();
            if (employeeId == null) {
                throw new BusinessRuleException("與教練對話時必須指定 employeeId");
            }
            requireActiveEmployee(employeeId);
            builder.employeeId(employeeId);
        } else if (request.employeeId() != null) {
            throw new BusinessRuleException("與 AI 顧問對話時不可指定 employeeId");
        }
        return builder.build();
    }

    private String roomIdForMember(Integer memberId, ChatCounterpartType type, Integer employeeId) {
        if (type == ChatCounterpartType.AI) {
            return "member_" + memberId + "_ai";
        }
        if (employeeId == null) {
            throw new BusinessRuleException("查詢與教練的對話紀錄時必須指定 employeeId");
        }
        return "member_" + memberId + "_employee_" + employeeId;
    }

    private String roomIdForEmployee(Integer employeeId, Integer memberId) {
        if (memberId == null) {
            throw new BusinessRuleException("必須指定要查詢的學員 memberId");
        }
        return "member_" + memberId + "_employee_" + employeeId;
    }

    private Integer resolveActiveMemberId(Integer accountId) {
        Member member = members.findByAccountId(accountId)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有會員身分"));
        if (member.getDeletedAt() != null || member.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenAccessException("此會員帳號目前無法使用");
        }
        return member.getId();
    }

    private Integer resolveActiveEmployeeId(Integer accountId) {
        Employee employee = employees.findByAccountId(accountId)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有員工身分"));
        if (employee.getDeletedAt() != null || employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new ForbiddenAccessException("此員工帳號目前無法使用");
        }
        return employee.getId();
    }

    private void requireActiveEmployee(Integer employeeId) {
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到教練／員工：" + employeeId));
        if (employee.getDeletedAt() != null || employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new ResourceNotFoundException("找不到教練／員工：" + employeeId);
        }
    }

    private void requireActiveMember(Integer memberId) {
        Member member = members.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到學員：" + memberId));
        if (member.getDeletedAt() != null || member.getStatus() != UserStatus.ACTIVE) {
            throw new ResourceNotFoundException("找不到學員：" + memberId);
        }
    }
}
