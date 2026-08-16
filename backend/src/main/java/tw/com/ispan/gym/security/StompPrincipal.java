package tw.com.ispan.gym.security;

import java.security.Principal;

/**
 * WebSocket 連線的登入識別，僅攜帶 account_id。
 *
 * <p>STOMP 連線與一般 HTTP 請求是不同的執行緒／生命週期，不會經過
 * {@link JwtAuthFilter} 也不共用 {@code SecurityContextHolder}；改由
 * {@link StompAuthChannelInterceptor} 在 CONNECT frame 驗證 JWT 後，
 * 把解析出的 account_id 存成這個 Principal，掛在 STOMP session 上，
 * 之後每個 frame（包含 {@code @MessageMapping} 方法的 Principal 參數）都能取用。
 */
public record StompPrincipal(Integer accountId) implements Principal {
    @Override
    public String getName() {
        return String.valueOf(accountId);
    }
}
