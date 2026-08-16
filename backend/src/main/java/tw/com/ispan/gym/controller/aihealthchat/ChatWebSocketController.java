package tw.com.ispan.gym.controller.aihealthchat;

import java.security.Principal;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.aihealthchat.ChatReplyRequest;
import tw.com.ispan.gym.dto.aihealthchat.ChatSendRequest;
import tw.com.ispan.gym.exception.ApiErrorResponse;
import tw.com.ispan.gym.exception.ApiException;
import tw.com.ispan.gym.security.StompPrincipal;
import tw.com.ispan.gym.service.aihealthchat.ChatService;

/**
 * 聊天室即時收發端點。接收端點 /app/chat.send（學員發起／回覆）與 /app/chat.reply
 * （員工回覆），推送頻道由 ChatService#saveAndPush 統一處理，本類別只負責轉交。
 *
 * <p>Principal 由 StompAuthChannelInterceptor 在 CONNECT frame 驗證 JWT 後掛上，
 * 型別固定是 {@link StompPrincipal}，攜帶 account_id；哪個 account 具備會員或員工身分、
 * 是否有效，交給 ChatService 內部反查與檢查，本類別不重複判斷。
 */
@Controller
public class ChatWebSocketController {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketController.class);

    private final ChatService chatService;

    public ChatWebSocketController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 學員發起／回覆訊息，對象為 AI顧問 或 教練 */
    @MessageMapping("/chat.send")
    public void send(@Payload @Valid ChatSendRequest request, Principal principal) {
        chatService.sendFromMember(accountId(principal), request);
    }

    /** 員工（教練／管理員）回覆指定學員 */
    @MessageMapping("/chat.reply")
    public void reply(@Payload @Valid ChatReplyRequest request, Principal principal) {
        chatService.replyFromEmployee(accountId(principal), request);
    }

    private Integer accountId(Principal principal) {
        return ((StompPrincipal) principal).accountId();
    }

    /** 業務例外（如無權存取、找不到對象）回報給發送者本人，訂閱 /user/queue/errors 才收得到 */
    @MessageExceptionHandler(ApiException.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleApiException(ApiException ex) {
        return new ApiErrorResponse(ex.getCode(), ex.getMessage(), LocalDateTime.now());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleUnexpected(Exception ex) {
        log.error("聊天室 WebSocket 發生未預期的錯誤", ex);
        return new ApiErrorResponse("INTERNAL_ERROR", "系統發生錯誤，請稍後再試", LocalDateTime.now());
    }
}
