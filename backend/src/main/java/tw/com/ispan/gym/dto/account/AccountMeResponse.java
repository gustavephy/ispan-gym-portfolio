package tw.com.ispan.gym.dto.account;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帳號身份快照，供帳號安全頁顯示與登入後刷新 {@code roles}（例如被管理者調整身分後，
 * 舊有 JWT／localStorage 裡的 roles 已經過期）。
 *
 * <p>登入方式（是否有本機密碼／Google 綁定）不重複放在這裡，一律以既有
 * {@link OAuthConnectionsResponse#hasPassword()} 為單一來源，避免同一頁有兩份可能不同步的狀態。
 */
public record AccountMeResponse(Integer accountId, String email, List<String> roles, LocalDateTime createdAt) {
}
