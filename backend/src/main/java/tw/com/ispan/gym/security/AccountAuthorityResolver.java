package tw.com.ispan.gym.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 把帳號展開成當下有效的權限清單——JwtAuthFilter（每請求授權）與
 * AuthController（登入回應的 roles 欄位，供前端導流）共用同一份規則，
 * 避免兩處各自實作而漂移。
 *
 * <p>展開規則：具 MEMBER 身分且會員 ACTIVE → ROLE_MEMBER；具 EMPLOYEE 身分且
 * 員工 ACTIVE → ROLE_EMPLOYEE（基底：是員工），另外 employee.role == ADMIN
 * 再加 ROLE_ADMIN、有對應且未刪除的 Trainer 資料列再加 ROLE_TRAINER——教練身分
 * 只看 Trainer.deletedAt，不看 Trainer.status（那只影響能否被預約，不影響教練
 * 身分本身）。未啟用的身分不授予任何權限，三種員工權限彼此也不互斥。
 */
@Component
public class AccountAuthorityResolver {

    public static final String ROLE_PREFIX = "ROLE_";

    private final MemberRepository members;
    private final EmployeeRepository employees;
    private final TrainerRepository trainers;

    public AccountAuthorityResolver(MemberRepository members, EmployeeRepository employees,
            TrainerRepository trainers) {
        this.members = members;
        this.employees = employees;
        this.trainers = trainers;
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
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + "EMPLOYEE"));
                if (employee.getRole() == EmployeeRole.ADMIN) {
                    authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + "ADMIN"));
                }
                trainers.findByEmployeeId(employee.getId())
                        .filter(t -> t.getDeletedAt() == null)
                        .ifPresent(t -> authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + "TRAINER")));
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
