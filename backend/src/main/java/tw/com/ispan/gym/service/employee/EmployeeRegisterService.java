package tw.com.ispan.gym.service.employee;

import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.employee.EmployeeReapplyRequest;
import tw.com.ispan.gym.dto.employee.EmployeeRegisterRequest;
import tw.com.ispan.gym.dto.employee.EmployeeRegisterResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;

/**
 * 員工自助申請（含日後的重新申請）。審核（核准／拒絕）在獨立的 {@link EmployeeReviewService}。
 *
 * <p>員工沒有 email 驗證迴圈（不像 Member 有 purpose/token/resend 那一整套）——
 * 新帳號直接 PENDING_APPROVAL，改靠任一 ACTIVE 員工人工審核轉 ACTIVE/REJECTED。
 */
@Service
@Transactional(readOnly = true)
public class EmployeeRegisterService {

    private final AccountRepository accounts;
    private final EmployeeRepository employees;
    private final PasswordEncoder passwordEncoder;

    public EmployeeRegisterService(
            AccountRepository accounts,
            EmployeeRepository employees,
            PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public EmployeeRegisterResponse register(EmployeeRegisterRequest request) {
        String email = normalizeEmail(request.getEmail());

        // 沒有 PENDING 重送這種分支——員工註冊不像會員有信件 token 需要重寄，
        // 帳號已存在（不論是會員限定、員工限定、待審核或任何狀態）一律擋下，
        // 訊息不透露對方實際狀態
        if (accounts.findByEmailIgnoreCase(email).isPresent()) {
            throw new BusinessRuleException("此 Email 已有帳號，請改用登入或聯繫管理員。");
        }

        Account account = new Account();
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        account.getRoles().add(AccountType.EMPLOYEE);
        account = accounts.save(account);

        Employee employee = new Employee();
        employee.setAccount(account);
        employee.setFirstName(request.getFirstName().trim());
        employee.setLastName(request.getLastName().trim());
        employee.setPhone(request.getPhone().trim());
        employee.setRole(EmployeeRole.EMPLOYEE); // 匿名申請不能自選角色，固定為一般員工
        employee.setHireDate(request.getHireDate());
        employee.setStatus(EmployeeStatus.PENDING_APPROVAL);
        employees.save(employee);

        return EmployeeRegisterResponse.of(email, "註冊成功，請等候審核您的帳號。");
    }

    /**
     * REJECTED 重新申請：不是登入員工系統（REJECTED 本來就拿不到 ROLE_EMPLOYEE），
     * 也不是靠 register() 匿名覆蓋既有帳號——申請者送出原 email＋密碼證明是本人，
     * 通過後才允許更新申請資料並重新送審，全程不簽發 JWT、不動密碼。
     */
    @Transactional
    public EmployeeRegisterResponse reapply(EmployeeReapplyRequest request) {
        String email = normalizeEmail(request.getEmail());
        Account account = accounts.findByEmailIgnoreCase(email)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessRuleException("Email 或密碼錯誤"));
        if (!passwordEncoder.matches(request.getPassword(), account.getPasswordHash())) {
            throw new BusinessRuleException("Email 或密碼錯誤");
        }

        // 密碼已驗證＝證明是本人，以下訊息可以具體，不算匿名洩漏。
        // 直接用鎖定查詢拿 Employee，不先做一次未鎖定的讀取——避免「讀到狀態之後、
        // 排隊等鎖期間，狀態被別的交易改掉」這個空窗期。後面所有檢查與寫入都用這個
        // 被鎖住的 employee，不再另外查一次。
        Employee employee = employees.findByAccountIdForUpdate(account.getId())
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessRuleException("此帳號沒有可重新申請的員工紀錄"));

        if (employee.getStatus() != EmployeeStatus.REJECTED) {
            throw new BusinessRuleException("此員工申請目前不是可重新申請的狀態");
        }

        employee.setFirstName(request.getFirstName().trim());
        employee.setLastName(request.getLastName().trim());
        employee.setPhone(request.getPhone().trim());
        employee.setHireDate(request.getHireDate());
        employee.setStatus(EmployeeStatus.PENDING_APPROVAL);
        employee.setReviewedBy(null);
        employee.setReviewedAt(null);
        employee.setReviewNote(null);

        return EmployeeRegisterResponse.of(email, "已重新送出申請，請等候審核。");
    }

    private String normalizeEmail(String rawEmail) {
        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }
}
