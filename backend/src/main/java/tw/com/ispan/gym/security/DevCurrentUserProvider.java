package tw.com.ispan.gym.security;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * dev 用的假登入身分，讓本機不必先登入拿 token 就能打 API。
 * 正式環境走 {@link UnconfiguredCurrentUserProvider}（{@code @Profile("!dev")}），
 * 由它從 SecurityContext 解析真正的登入者，兩者以 profile 互斥。
 *
 * <p><b>2026-08-18 修正：</b>WebSocket 聊天室的 STOMP 驗證在所有 profile 都會真的解析
 * JWT（{@link StompAuthChannelInterceptor} 不吃 dev 捷徑，見其類別註解），若這裡永遠
 * 回傳固定的假身分，只要目前登入帳號的 account_id／member_id 剛好不等於
 * {@code app.dev-current-user-id}，REST（如 {@code /api/members/me}）跟 WebSocket
 * 對「我是誰」的認知就會兜不起來——前端拿假 memberId 組出的聊天室房間，WebSocket 那邊
 * 用真實身分一查其實不是這個人的房間，訂閱直接被 authorizeRoomSubscription 擋掉，
 * STOMP 連線被迫斷線重連（client 端看到的錯誤是
 * "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]"）。
 *
 * <p>{@link JwtAuthFilter} 是不分 profile 都會執行的一般 servlet filter：只要請求帶有效
 * 的 {@code Authorization: Bearer <token>}，它就會把真實登入者寫入 SecurityContext
 * （dev 底下 {@link DevSecurityConfig} 只影響「要不要擋」，不影響「有沒有解析 token」）。
 * 因此這裡優先嘗試從 SecurityContext 讀出真實登入者，找得到就回傳真實 id，
 * 藉此讓「有登入」的一般使用情境下 REST 與 WebSocket 的身分判斷一致；
 * 完全沒帶 token（或 token 無效）時才退回固定的假身分，保留「本機不登入也能打 API」
 * 的原始便利性。
 */
@Component
@Profile("dev")
public class DevCurrentUserProvider implements CurrentUserProvider {
    private final Integer fallbackUserId;
    private final Integer fallbackMemberId;
    private final Integer fallbackEmployeeId;
    private final AccountRepository accounts;
    private final MemberRepository members;
    private final EmployeeRepository employees;

    public DevCurrentUserProvider(
            @Value("${app.dev-current-user-id}") Integer currentUserId,
            @Value("${app.dev-current-member-id:${app.dev-current-user-id}}") Integer currentMemberId,
            @Value("${app.dev-current-employee-id:${app.dev-current-user-id}}") Integer currentEmployeeId,
            AccountRepository accounts, MemberRepository members, EmployeeRepository employees) {
        this.fallbackUserId = currentUserId;
        this.fallbackMemberId = currentMemberId;
        this.fallbackEmployeeId = currentEmployeeId;
        this.accounts = accounts;
        this.members = members;
        this.employees = employees;
    }

    @Override
    public Integer requireCurrentUserId() {
        return resolveRealAccount().map(Account::getId).orElse(fallbackUserId);
    }

    @Override
    public Integer requireCurrentMemberId() {
        return resolveRealAccount()
                .flatMap(account -> members.findByAccountId(account.getId()))
                .filter(m -> m.getDeletedAt() == null && m.getStatus() == UserStatus.ACTIVE)
                .map(Member::getId)
                .orElse(fallbackMemberId);
    }

    @Override
    public Integer requireCurrentEmployeeId() {
        return resolveRealAccount()
                .flatMap(account -> employees.findByAccountId(account.getId()))
                .filter(e -> e.getDeletedAt() == null && e.getStatus() == EmployeeStatus.ACTIVE)
                .map(Employee::getEmployeeId)
                .orElse(fallbackEmployeeId);
    }

    /**
     * 只有 {@link JwtAuthFilter} 真的解析出合法登入者時才有值；完全沒帶 token、token
     * 無效／過期，或帳號已被刪除時，SecurityContext 裡會是 Spring Security 預設的
     * anonymousUser，此時回傳空，讓呼叫端退回寫死的假身分。
     */
    private Optional<Account> resolveRealAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return Optional.empty();
        }
        // JwtAuthFilter 把 email 存進 UsernamePasswordAuthenticationToken 的 name
        return accounts.findByEmailIgnoreCase(authentication.getName());
    }
}
