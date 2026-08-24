package tw.com.ispan.gym.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 這支是聊天室唯一的認證關卡，涵蓋兩個獨立面向：
 * CONNECT 的 JWT 驗證（缺 header／格式錯／token 失效／帳號不存在或已刪）、
 * 以及 SUBSCRIBE 的房間授權——後者是防止「任何已登入帳號都能訂閱
 * /topic/room/member_1_ai 偷看別人私訊」的唯一防線，因此把跨帳號訂閱
 * 列為重點測試情境。
 *
 * 訊息一律用 setLeaveMutable(true) 建立，才能重現正式環境中
 * MessageHeaderAccessor.getAccessor() 取得可變 accessor 的行為；
 * 這也讓「CONNECT 後 Principal 有沒有真的掛上」變成可驗證的斷言
 * （見類別註解提到的 wrap() 陷阱）。
 */
class StompAuthChannelInterceptorTest {

    private static final Integer ACCOUNT_ID = 1;
    private static final Integer MEMBER_ID = 10;
    private static final Integer EMPLOYEE_ID = 20;
    private static final String TOKEN = "valid.jwt.token";

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final MessageChannel channel = mock(MessageChannel.class);

    private final StompAuthChannelInterceptor interceptor =
            new StompAuthChannelInterceptor(jwtUtil, accounts, members, employees);

    // === 共用測試資料 ========================================================

    private Account account() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("user@example.com");
        return account;
    }

    private Member member(Integer id, UserStatus status) {
        Member member = new Member();
        member.setId(id);
        member.setStatus(status);
        return member;
    }

    private Employee employee(Integer id, EmployeeStatus status) {
        Employee employee = new Employee();
        ReflectionTestUtils.setField(employee, "employeeId", id);
        employee.setStatus(status);
        return employee;
    }

    /** CONNECT frame；authHeader 傳 null 代表完全不帶 Authorization */
    private StompHeaderAccessor connectFrame(String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authHeader != null) {
            accessor.setNativeHeader("Authorization", authHeader);
        }
        accessor.setLeaveMutable(true);
        return accessor;
    }

    /** SUBSCRIBE frame；principal 傳 null 代表尚未通過 CONNECT 驗證 */
    private StompHeaderAccessor subscribeFrame(String destination, StompPrincipal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (principal != null) {
            accessor.setUser(principal);
        }
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private Message<byte[]> toMessage(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private void stubValidToken() {
        when(jwtUtil.extractAccountId(TOKEN)).thenReturn(ACCOUNT_ID);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
    }

    // === CONNECT：JWT 驗證 ====================================================

    @Test
    void 帶有效token的連線通過並把帳號id掛成Principal() {
        stubValidToken();
        StompHeaderAccessor accessor = connectFrame("Bearer " + TOKEN);

        interceptor.preSend(toMessage(accessor), channel);

        // 關鍵：Principal 必須真的掛在往下傳的 accessor 上，
        // 否則後續每個 frame 的 getUser() 都會是 null
        assertThat(accessor.getUser()).isInstanceOf(StompPrincipal.class);
        assertThat(((StompPrincipal) accessor.getUser()).accountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void 完全不帶Authorization的連線擋下() {
        assertThatThrownBy(() -> interceptor.preSend(toMessage(connectFrame(null)), channel))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Bearer");
    }

    @Test
    void 非Bearer格式的Authorization擋下() {
        assertThatThrownBy(() -> interceptor.preSend(toMessage(connectFrame("Basic abc123")), channel))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void Bearer後面沒有token擋下() {
        assertThatThrownBy(() -> interceptor.preSend(toMessage(connectFrame("Bearer    ")), channel))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void token無效或過期擋下且不查帳號() {
        when(jwtUtil.extractAccountId(TOKEN)).thenThrow(new JwtException("expired"));

        assertThatThrownBy(() -> interceptor.preSend(toMessage(connectFrame("Bearer " + TOKEN)), channel))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("無效或已過期");
    }

    @Test
    void token解析出的帳號不存在擋下() {
        when(jwtUtil.extractAccountId(TOKEN)).thenReturn(ACCOUNT_ID);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(toMessage(connectFrame("Bearer " + TOKEN)), channel))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("不存在或已停用");
    }

    @Test
    void 已軟刪除的帳號即使token仍有效也擋下() {
        Account deleted = account();
        deleted.softDelete();
        when(jwtUtil.extractAccountId(TOKEN)).thenReturn(ACCOUNT_ID);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> interceptor.preSend(toMessage(connectFrame("Bearer " + TOKEN)), channel))
                .isInstanceOf(BadCredentialsException.class);
    }

    // === CONNECT：credentialsVersion（密碼變更後舊 token 全裝置失效）================
    // 密碼管理功能新增（2026-08-21），比照 JwtAuthFilter 的同一套比對邏輯，
    // 只是套用在 CONNECT frame 而非每次 HTTP 請求。

    @Test
    void credentialsVersion不符時擋下連線_沿用同一句訊息不揭露密碼被改過() {
        when(jwtUtil.extractAccountId(TOKEN)).thenReturn(ACCOUNT_ID);
        when(jwtUtil.extractCredentialsVersion(TOKEN)).thenReturn(5);
        Account account = account();
        account.setCredentialsVersion(6); // 帳號已經改過密碼，token 裡的版本是變更前的舊版
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> interceptor.preSend(toMessage(connectFrame("Bearer " + TOKEN)), channel))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("無效或已過期");
    }

    @Test
    void credentialsVersion相符時正常放行並掛上Principal() {
        when(jwtUtil.extractAccountId(TOKEN)).thenReturn(ACCOUNT_ID);
        when(jwtUtil.extractCredentialsVersion(TOKEN)).thenReturn(4);
        Account account = account();
        account.setCredentialsVersion(4);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        StompHeaderAccessor accessor = connectFrame("Bearer " + TOKEN);
        interceptor.preSend(toMessage(accessor), channel);

        assertThat(accessor.getUser()).isInstanceOf(StompPrincipal.class);
    }

    /** jwtUtil 是純 mock，未特別 stub 時 extractCredentialsVersion 回傳 int 預設值 0；
     *  帳號的 credentialsVersion 同樣預設 0，兩者相符——確認既有測試（都用 stubValidToken()）
     *  在加了 cv 比對之後不會意外全部變成連不上。 */
    @Test
    void 未特別stub版本時兩邊都是預設值0仍能通過() {
        stubValidToken();

        StompHeaderAccessor accessor = connectFrame("Bearer " + TOKEN);
        interceptor.preSend(toMessage(accessor), channel);

        assertThat(accessor.getUser()).isInstanceOf(StompPrincipal.class);
    }

    // === SUBSCRIBE：房間授權（跨帳號防護）======================================

    @Test
    void 學員可訂閱自己的AI房間() {
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member(MEMBER_ID, UserStatus.ACTIVE)));
        Message<byte[]> message = toMessage(
                subscribeFrame("/topic/room/member_" + MEMBER_ID + "_ai", new StompPrincipal(ACCOUNT_ID)));

        assertThatCode(() -> interceptor.preSend(message, channel)).doesNotThrowAnyException();
    }

    @Test
    void 學員訂閱別人的AI房間被擋下() {
        // 登入者是 member 10，卻想訂閱 member 99 的房間
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member(MEMBER_ID, UserStatus.ACTIVE)));
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        Message<byte[]> message = toMessage(
                subscribeFrame("/topic/room/member_99_ai", new StompPrincipal(ACCOUNT_ID)));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("無權訂閱");
    }

    @Test
    void 學員可訂閱自己與教練的房間() {
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member(MEMBER_ID, UserStatus.ACTIVE)));
        Message<byte[]> message = toMessage(subscribeFrame(
                "/topic/room/member_" + MEMBER_ID + "_employee_" + EMPLOYEE_ID, new StompPrincipal(ACCOUNT_ID)));

        assertThatCode(() -> interceptor.preSend(message, channel)).doesNotThrowAnyException();
    }

    @Test
    void 教練可訂閱自己與學員的房間() {
        // 登入者不是會員，但是房間另一端的員工
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(employees.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(employee(EMPLOYEE_ID, EmployeeStatus.ACTIVE)));
        Message<byte[]> message = toMessage(subscribeFrame(
                "/topic/room/member_" + MEMBER_ID + "_employee_" + EMPLOYEE_ID, new StompPrincipal(ACCOUNT_ID)));

        assertThatCode(() -> interceptor.preSend(message, channel)).doesNotThrowAnyException();
    }

    @Test
    void 教練訂閱別的教練與學員的房間被擋下() {
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(employees.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(employee(EMPLOYEE_ID, EmployeeStatus.ACTIVE)));
        // 房間屬於 employee 99
        Message<byte[]> message = toMessage(subscribeFrame(
                "/topic/room/member_" + MEMBER_ID + "_employee_99", new StompPrincipal(ACCOUNT_ID)));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 教練不能訂閱學員與AI的私人房間() {
        // AI 房間沒有員工那一側，員工身分不構成授權依據
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(employees.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(employee(EMPLOYEE_ID, EmployeeStatus.ACTIVE)));
        Message<byte[]> message = toMessage(
                subscribeFrame("/topic/room/member_" + MEMBER_ID + "_ai", new StompPrincipal(ACCOUNT_ID)));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 停權或已刪除的學員不能訂閱自己的房間() {
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member(MEMBER_ID, UserStatus.SUSPENDED)));
        Message<byte[]> suspended = toMessage(
                subscribeFrame("/topic/room/member_" + MEMBER_ID + "_ai", new StompPrincipal(ACCOUNT_ID)));
        assertThatThrownBy(() -> interceptor.preSend(suspended, channel))
                .isInstanceOf(AccessDeniedException.class);

        Member deleted = member(MEMBER_ID, UserStatus.ACTIVE);
        deleted.setDeletedAt(LocalDateTime.now());
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(deleted));
        Message<byte[]> softDeleted = toMessage(
                subscribeFrame("/topic/room/member_" + MEMBER_ID + "_ai", new StompPrincipal(ACCOUNT_ID)));
        assertThatThrownBy(() -> interceptor.preSend(softDeleted, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 尚未完成連線驗證就訂閱被擋下() {
        Message<byte[]> message = toMessage(
                subscribeFrame("/topic/room/member_" + MEMBER_ID + "_ai", null));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("尚未完成驗證");
    }

    @Test
    void 格式不符的房間目的地被擋下() {
        Message<byte[]> message = toMessage(
                subscribeFrame("/topic/room/../secret", new StompPrincipal(ACCOUNT_ID)));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("格式錯誤");
    }

    @Test
    void 非房間頻道的訂閱不做房間授權() {
        // 例如 @SendToUser 的個人錯誤回報頻道，不屬於房間廣播
        Message<byte[]> message = toMessage(
                subscribeFrame("/user/queue/errors", new StompPrincipal(ACCOUNT_ID)));

        assertThatCode(() -> interceptor.preSend(message, channel)).doesNotThrowAnyException();
    }

    // === 其他 frame ===========================================================

    @Test
    void 非CONNECT與SUBSCRIBE的frame原樣放行() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/chat.send");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = toMessage(accessor);

        // SEND 沿用 CONNECT 當時掛上的 Principal，不必每個 frame 重驗
        assertThatCode(() -> interceptor.preSend(message, channel)).doesNotThrowAnyException();
    }
}
