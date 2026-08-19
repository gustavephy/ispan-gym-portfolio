package tw.com.ispan.gym.controller.aihealthchat;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tw.com.ispan.gym.dto.aihealthchat.ChatConversationSummary;
import tw.com.ispan.gym.dto.aihealthchat.ChatMessageDTO;
import tw.com.ispan.gym.dto.aihealthchat.ChatUnreadCountResponse;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage.ChatCounterpartType;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.aihealthchat.ChatService;

/**
 * 聊天室的歷史訊息／已讀／未讀數查詢，即時收發走 WebSocket（ChatWebSocketController）。
 * 分成學員端（/api/chat/**）與員工端（/api/employee/chat/**）兩組路徑，
 * 對應聊天室固定只分「AI顧問」「教練」兩個分頁的設計——學員端用 counterpartType
 * 切換分頁，員工端固定只服務 counterpartType=EMPLOYEE 的對話（教練不會收到 AI 分頁的訊息）。
 */
@RestController
public class ChatController {
    private final ChatService chatService;
    private final CurrentUserProvider currentUser;

    public ChatController(ChatService chatService, CurrentUserProvider currentUser) {
        this.chatService = chatService;
        this.currentUser = currentUser;
    }

    // ───────────────────────── 學員端 ─────────────────────────

    @GetMapping("/api/chat/history")
    public Page<ChatMessageDTO> getHistory(
            @RequestParam ChatCounterpartType counterpartType,
            @RequestParam(required = false) Integer employeeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return chatService.getHistoryForMember(currentUser.requireCurrentMemberId(), counterpartType, employeeId,
                page, size);
    }

    @PatchMapping("/api/chat/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAsRead(
            @RequestParam ChatCounterpartType counterpartType,
            @RequestParam(required = false) Integer employeeId) {
        chatService.markReadForMember(currentUser.requireCurrentMemberId(), counterpartType, employeeId);
    }

    @GetMapping("/api/chat/unread-count")
    public ChatUnreadCountResponse getUnreadCount() {
        return new ChatUnreadCountResponse(chatService.unreadCountForMember(currentUser.requireCurrentMemberId()));
    }

    /**
     * 學員在「AI顧問」分頁主動按「推薦課程」時呼叫，只是觸發，不等 AI 生成結果——
     * 立刻回 202，實際推薦訊息稍後透過 WebSocket 推播到 member_{id}_ai 房間
     * （與一般 AI 回覆共用同一套 saveAndPush 機制，見 ChatService/ChatAiResponderService）。
     */
    @PostMapping("/api/chat/recommend-course")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void recommendCourse() {
        chatService.requestCourseRecommendation(currentUser.requireCurrentMemberId());
    }

    // ───────────────────────── 員工端 ─────────────────────────

    @GetMapping("/api/employee/chat/history")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public Page<ChatMessageDTO> getHistoryForEmployee(
            @RequestParam Integer memberId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return chatService.getHistoryForEmployeeAccount(currentUser.requireCurrentUserId(), memberId, page, size);
    }

    @PatchMapping("/api/employee/chat/read")
    @PreAuthorize("hasRole('EMPLOYEE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAsReadByEmployee(@RequestParam Integer memberId) {
        chatService.markReadForEmployeeAccount(currentUser.requireCurrentUserId(), memberId);
    }

    @GetMapping("/api/employee/chat/unread-count")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ChatUnreadCountResponse getUnreadCountForEmployee() {
        return new ChatUnreadCountResponse(chatService.unreadCountForEmployeeAccount(currentUser.requireCurrentUserId()));
    }

    /** 「學員訊息」列表：曾與我對話過的學員，依最後訊息時間排序，附預覽與未讀數 */
    @GetMapping("/api/employee/chat/conversations")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<ChatConversationSummary> getConversationsForEmployee() {
        return chatService.listConversationsForEmployeeAccount(currentUser.requireCurrentUserId());
    }
}
