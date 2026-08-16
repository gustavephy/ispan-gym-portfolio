package tw.com.ispan.gym.security;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * dev 用的假登入身分。TODO: replace with authenticated user from Spring Security/JWT.
 *
 * <p>注意：member_id／employee_id 與 account_id 已是各自獨立的流水號，這裡三個方法
 * 預設都回傳同一個設定值（app.dev-current-user-id）純屬 dev 便宜行事——本機測試時請確保
 * 你要模擬的身分與 account 恰好同號（全新資料庫依序註冊即會如此），
 * 否則請分開設定 app.dev-current-member-id／app.dev-current-employee-id。
 */
@Component
@Profile("dev")
public class DevCurrentUserProvider implements CurrentUserProvider {
    private final Integer currentUserId;
    private final Integer currentMemberId;
    private final Integer currentEmployeeId;

    public DevCurrentUserProvider(
            @Value("${app.dev-current-user-id}") Integer currentUserId,
            @Value("${app.dev-current-member-id:${app.dev-current-user-id}}") Integer currentMemberId,
            @Value("${app.dev-current-employee-id:${app.dev-current-user-id}}") Integer currentEmployeeId) {
        this.currentUserId = currentUserId;
        this.currentMemberId = currentMemberId;
        this.currentEmployeeId = currentEmployeeId;
    }

    @Override
    public Integer requireCurrentUserId() {
        return currentUserId;
    }

    @Override
    public Integer requireCurrentMemberId() {
        return currentMemberId;
    }

    @Override
    public Integer requireCurrentEmployeeId() {
        return currentEmployeeId;
    }
}
