package tw.com.ispan.gym.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 把帳號展開成當下有效的權限清單——JwtAuthFilter（每請求授權）與
 * AuthController（登入回應的 roles 欄位，供前端導流）共用同一份規則，
 * 避免兩處各自實作而漂移。
 *
 * <p>展開規則：具 MEMBER 身分且會員 ACTIVE → ROLE_MEMBER；具 EMPLOYEE 身分且
 * 員工 ACTIVE → ROLE_EMPLOYEE（基底：是員工）＋ ROLE_ADMIN／ROLE_TRAINER
 * （職位，依 employee.role）。未啟用的身分不授予任何權限。
 */
@Component
public class AccountAuthorityResolver {

    public static final String ROLE_PREFIX = "ROLE_";

    private final MemberRepository members;
    private final EmployeeRepository employees;

    public AccountAuthorityResolver(MemberRepository members, EmployeeRepository employees) {
        this.members = members;
        this.employees = employees;
    }

    public List<SimpleGrantedAuthority> resolve(Account account) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();

        if (account.getRoles().contains(AccountType.MEMBER)) {
            Member member = members.findByAccountId(account.getId()).orElse(null);
            if (member != null && member.getDeletedAt() == null && member.getStatus() == UserStatus.ACTIVE) {
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + "MEMBER"));
            }
        }

        if (account.getRoles().contains(AccountType.EMPLOYEE)) {
            Employee employee = employees.findByAccountId(account.getId()).orElse(null);
            if (employee != null && employee.getDeletedAt() == null
                    && employee.getStatus() == EmployeeStatus.ACTIVE) {
                // 基底角色＋職位角色兩層：見類別 javadoc
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + "EMPLOYEE"));
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + employee.getRole().name()));
            }
        }
        return authorities;
    }

    /** 去掉 ROLE_ 前綴的名稱清單（如 [MEMBER] 或 [EMPLOYEE, TRAINER]），給 API 回應用 */
    public List<String> resolveRoleNames(Account account) {
        return resolve(account).stream()
                .map(a -> a.getAuthority().substring(ROLE_PREFIX.length()))
                .toList();
    }
}
