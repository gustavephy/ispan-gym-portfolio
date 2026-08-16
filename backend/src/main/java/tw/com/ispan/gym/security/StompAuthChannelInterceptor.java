package tw.com.ispan.gym.security;

import io.jsonwebtoken.JwtException;
import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 聊天室 WebSocket 的登入驗證：只在 CONNECT frame 檢查一次，通過後把
 * {@link StompPrincipal} 掛在 session 上，同一連線後續的 SEND／SUBSCRIBE
 * frame 由 Spring 自動沿用，不必每個 frame 重驗。
 *
 * <p>Token 走 STOMP 原生 header（{@code Authorization: Bearer <token>}），
 * 而非 SockJS 交握 URL 的 query string——後者會被記錄在伺服器 access log／
 * 瀏覽器歷史紀錄裡，前者只存在於 WebSocket frame payload 中，較不容易外洩。
 *
 * <p>刻意不比照 REST API 在 dev profile 下放行匿名連線：WebSocket 是全新的
 * 通道，沒有對應的 DevSecurityConfig 可以連動放寬，維持「一律要求有效 JWT」
 * 最簡單也最不容易留下認證漏洞；本機測試時一樣先呼叫 /api/auth/login 換 token。
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern ROOM_DESTINATION = Pattern.compile(
            "^/topic/room/member_(\\d+)_(ai|employee_(\\d+))$");

    private final JwtUtil jwtUtil;
    private final AccountRepository accounts;
    private final MemberRepository members;
    private final EmployeeRepository employees;

    public StompAuthChannelInterceptor(JwtUtil jwtUtil, AccountRepository accounts,
            MemberRepository members, EmployeeRepository employees) {
        this.jwtUtil = jwtUtil;
        this.accounts = accounts;
        this.members = members;
        this.employees = employees;
    }

    /**
     * 一定要用 {@code getAccessor}（拿到跟 message 綁在一起、仍可變動的同一份 accessor），
     * 不能用 {@code StompHeaderAccessor.wrap(message)}——那個方法會複製出一份全新、
     * 脫離原本 message 的 accessor，對它呼叫 setUser() 完全不會反映到實際往下傳遞的
     * message 上：CONNECT 因此「看似成功」卻沒有真的把 Principal 掛上 session，
     * 導致後續每一個 frame 的 accessor.getUser() 都是 null。
     */
    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            accessor.setUser(authenticate(accessor));
        }
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeRoomSubscription(accessor);
        }
        return message;
    }

    private StompPrincipal authenticate(StompHeaderAccessor accessor) {
        String token = extractToken(accessor.getFirstNativeHeader("Authorization"));
        Integer accountId;
        try {
            accountId = jwtUtil.extractAccountId(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BadCredentialsException("聊天室連線的 token 無效或已過期");
        }

        Account account = accounts.findById(accountId).orElse(null);
        if (account == null || account.getDeletedAt() != null) {
            throw new BadCredentialsException("聊天室連線對應的帳號不存在或已停用");
        }
        return new StompPrincipal(account.getId());
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BadCredentialsException("聊天室連線缺少 Authorization: Bearer <token>");
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw new BadCredentialsException("聊天室連線缺少 Authorization: Bearer <token>");
        }
        return token;
    }

    /**
     * 房間 topic 是廣播頻道，不能只靠難猜的 room id 保護。若不在訂閱時授權，任何已登入帳號
     * 都能訂閱 {@code /topic/room/member_1_ai} 並讀取別人的私人訊息。
     */
    private void authorizeRoomSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/topic/room/")) {
            return;
        }
        Matcher matcher = ROOM_DESTINATION.matcher(destination);
        if (!matcher.matches()) {
            throw new AccessDeniedException("聊天室訂閱目的地格式錯誤");
        }
        Principal principal = accessor.getUser();
        if (!(principal instanceof StompPrincipal stompPrincipal)) {
            throw new AccessDeniedException("聊天室連線尚未完成驗證");
        }

        Integer memberId = Integer.valueOf(matcher.group(1));
        Integer employeeId = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
        Integer accountId = stompPrincipal.accountId();
        if (isActiveMemberForRoom(accountId, memberId)
                || (employeeId != null && isActiveEmployeeForRoom(accountId, employeeId))) {
            return;
        }
        throw new AccessDeniedException("無權訂閱此聊天室");
    }

    private boolean isActiveMemberForRoom(Integer accountId, Integer memberId) {
        return members.findByAccountId(accountId)
                .filter(member -> member.getId().equals(memberId)
                        && member.getDeletedAt() == null && member.getStatus() == UserStatus.ACTIVE)
                .isPresent();
    }

    private boolean isActiveEmployeeForRoom(Integer accountId, Integer employeeId) {
        return employees.findByAccountId(accountId)
                .filter(employee -> employee.getId().equals(employeeId)
                        && employee.getDeletedAt() == null && employee.getStatus() == EmployeeStatus.ACTIVE)
                .isPresent();
    }
}
