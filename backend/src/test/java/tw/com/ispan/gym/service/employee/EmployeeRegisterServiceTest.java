package tw.com.ispan.gym.service.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import tw.com.ispan.gym.dto.employee.EmployeeReapplyRequest;
import tw.com.ispan.gym.dto.employee.EmployeeRegisterRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;

/**
 * 純 Mockito 單元測試（比照 MemberRegisterServiceTest 的做法，不用 @SpringBootTest）。
 * 涵蓋 register() 的全新成功／既有帳號一律擋下，以及 reapply() 的密碼驗證、
 * REJECTED 才可重新申請、成功時清空審核紀錄且不覆蓋密碼。
 */
class EmployeeRegisterServiceTest {

    private static final Integer ACCOUNT_ID = 8;
    private static final Integer EMPLOYEE_ID = 80;
    private static final String EMAIL = "employee@example.com";

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final EmployeeRegisterService service =
            new EmployeeRegisterService(accounts, employees, passwordEncoder);

    // === 共用測試資料 ========================================================

    private EmployeeRegisterRequest registerRequest() {
        EmployeeRegisterRequest request = new EmployeeRegisterRequest();
        request.setFirstName("小華");
        request.setLastName("陳");
        request.setEmail(EMAIL);
        request.setPhone("0911111111");
        request.setPassword("Passw0rd123");
        request.setHireDate(LocalDate.now());
        return request;
    }

    private EmployeeReapplyRequest reapplyRequest() {
        EmployeeReapplyRequest request = new EmployeeReapplyRequest();
        request.setEmail(EMAIL);
        request.setPassword("Passw0rd123");
        request.setFirstName("小華2");
        request.setLastName("陳2");
        request.setPhone("0922222222");
        request.setHireDate(LocalDate.now());
        return request;
    }

    private Account account() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail(EMAIL);
        account.setPasswordHash("old-hashed-password");
        return account;
    }

    private Employee employee(Account account, EmployeeStatus status) {
        Employee employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        employee.setAccount(account);
        employee.setFirstName("舊名字");
        employee.setLastName("舊姓氏");
        employee.setPhone("0900000000");
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(status);
        employee.setHireDate(LocalDate.now());
        return employee;
    }

    // === register() =============================================================

    @Test
    void register_全新成功_角色固定EMPLOYEE狀態PENDING_APPROVAL() {
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(accounts.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.register(registerRequest());

        assertThat(response.email()).isEqualTo(EMAIL);
        var employeeCaptor = org.mockito.ArgumentCaptor.forClass(Employee.class);
        verify(employees).save(employeeCaptor.capture());
        Employee saved = employeeCaptor.getValue();
        assertThat(saved.getRole()).isEqualTo(EmployeeRole.EMPLOYEE);
        assertThat(saved.getStatus()).isEqualTo(EmployeeStatus.PENDING_APPROVAL);
    }

    @Test
    void register_email已有帳號時一律409_不分狀態() {
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account()));

        assertThatThrownBy(() -> service.register(registerRequest()))
                .isInstanceOf(BusinessRuleException.class);
        verify(accounts, never()).save(any());
        verify(employees, never()).save(any());
    }

    // === reapply() ===============================================================

    @Test
    void reapply_email不存在時回通用錯誤() {
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reapply(reapplyRequest()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Email 或密碼錯誤");
    }

    @Test
    void reapply_帳號已軟刪除時回通用錯誤() {
        Account account = account();
        account.softDelete();
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.reapply(reapplyRequest()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Email 或密碼錯誤");
    }

    @Test
    void reapply_密碼錯誤時回通用錯誤_跟email不存在同一句() {
        Account account = account();
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.reapply(reapplyRequest()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Email 或密碼錯誤");
        verify(employees, never()).findByAccountIdForUpdate(any());
    }

    @Test
    void reapply_密碼正確但沒有員工紀錄時回具體錯誤() {
        Account account = account();
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(employees.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reapply(reapplyRequest()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("此帳號沒有可重新申請的員工紀錄");
    }

    @Test
    void reapply_員工存在但非REJECTED時回具體錯誤() {
        Account account = account();
        Employee employee = employee(account, EmployeeStatus.PENDING_APPROVAL);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(employees.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.reapply(reapplyRequest()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("此員工申請目前不是可重新申請的狀態");
    }

    @Test
    void reapply_REJECTED成功_狀態回PENDING_APPROVAL且清空審核紀錄_不覆蓋密碼() {
        Account account = account();
        Employee employee = employee(account, EmployeeStatus.REJECTED);
        Employee oldReviewer = new Employee();
        oldReviewer.setEmployeeId(99);
        employee.setReviewedBy(oldReviewer);
        employee.setReviewedAt(java.time.LocalDateTime.now());
        employee.setReviewNote("資料不齊全");

        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(employees.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(employee));

        var response = service.reapply(reapplyRequest());

        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.PENDING_APPROVAL);
        assertThat(employee.getReviewedBy()).isNull();
        assertThat(employee.getReviewedAt()).isNull();
        assertThat(employee.getReviewNote()).isNull();
        assertThat(employee.getFirstName()).isEqualTo("小華2"); // 申請資料確實更新
        assertThat(account.getPasswordHash()).isEqualTo("old-hashed-password"); // 密碼沒被改
        verify(passwordEncoder, never()).encode(anyString());
        verify(employees, never()).findByAccountId(any());
        verify(employees, never()).findByIdForUpdate(any());
    }
}
