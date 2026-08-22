package tw.com.ispan.gym.security;

import org.springframework.stereotype.Component;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 帳號是否可以登入的單一判斷來源——密碼登入（AuthController）與 Google OAuth
 * 登入／兌換 JWT 都必須呼叫這裡，不能各自重算，避免兩邊邏輯漂移
 * （原本這段判定只寫在 AuthController 裡，2026-08-22 抽出來供 OAuth 共用）。
 *
 * <p>只回答「能不能登入」與「有哪些目前有效的身分」，不負責：查 Google 綁定、
 * 建立 Account、簽發 JWT、決定前端要導去哪裡、或記錄任何 OAuth transaction——
 * 那些是呼叫端各自的責任。
 */
@Component
public class AccountLoginPolicy {

    private final MemberRepository members;
    private final EmployeeRepository employees;

    public AccountLoginPolicy(MemberRepository members, EmployeeRepository employees) {
        this.members = members;
        this.employees = employees;
    }

    /** Account 未軟刪除，且至少有一個目前有效的身分（ACTIVE Member 或 ACTIVE Employee） */
    public boolean isLoginAllowed(Account account) {
        if (account.getDeletedAt() != null) {
            return false;
        }
        return hasActiveMember(account) || hasActiveEmployee(account);
    }

    public boolean hasActiveMember(Account account) {
        return members.findByAccountId(account.getId())
                .filter(m -> m.getDeletedAt() == null && m.getStatus() == UserStatus.ACTIVE)
                .isPresent();
    }

    public boolean hasActiveEmployee(Account account) {
        return employees.findByAccountId(account.getId())
                .filter(e -> e.getDeletedAt() == null && e.getStatus() == EmployeeStatus.ACTIVE)
                .isPresent();
    }
}
