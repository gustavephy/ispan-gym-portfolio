package tw.com.ispan.gym.dto.aihealthchat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 員工（教練／管理員）回覆學員訊息的請求格式，對應 STOMP 目的地 /app/chat.reply。
 * 員工可能同時服務多位學員，因此必須明確指定 memberId 是要回覆哪一位——
 * 不像學員端的 /app/chat.send 只需指定「對象類型」，因為學員自己的身分已由連線時的
 * JWT 決定，room 的另一邊（哪個學員）反而是員工這端才需要外部指定的資訊。
 */
public record ChatReplyRequest(
        @NotBlank @Size(max = 500) String content,
        @NotNull Integer memberId) {
}
