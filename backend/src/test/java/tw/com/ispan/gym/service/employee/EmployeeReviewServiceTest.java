package tw.com.ispan.gym.service.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;

/**
 * 純 Mockito 單元測試（比照 MemberRegisterServiceTest 的做法，不用 @SpringBootTest）。
 * 涵蓋 listPending()／approve()／reject() 的正常路徑與各種 guard 情境，
 * 併發鎖只驗證「有沒有呼叫 findByIdForUpdate 取鎖」，不模擬真正的多執行緒競爭。
 */
class EmployeeReviewServiceTest {

    private static final Integer EMPLOYEE_ID = 80;
    private static final Integer REVIEWER_ID = 81;

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final EmployeeReviewService service = new EmployeeReviewService(employees);

    private Employee pendingEmployee() {
        Account account = new Account();
        account.setId(8);
        account.setEmail("applicant@example.com");

        Employee employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        employee.setAccount(account);
        employee.setFirstName("小華");
        employee.setLastName("陳");
        employee.setPhone("0911111111");
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(EmployeeStatus.PENDING_APPROVAL);
        employee.setHireDate(LocalDate.now());
        return employee;
    }

    private Employee reviewer() {
        Employee reviewer = new Employee();
        reviewer.setEmployeeId(REVIEWER_ID);
        reviewer.setRole(EmployeeRole.EMPLOYEE);
        reviewer.setStatus(EmployeeStatus.ACTIVE);
        return reviewer;
    }

    // === listPending() ========================================================

    @Test
    void listPending_只查PENDING_APPROVAL狀態() {
        when(employees.findByStatusAndDeletedAtIsNull(EmployeeStatus.PENDING_APPROVAL))
                .thenReturn(List.of(pendingEmployee()));

        var result = service.listPending();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).email()).isEqualTo("applicant@example.com");
        assertThat(result.get(0).role()).isEqualTo("EMPLOYEE");
    }

    // === approve() =============================================================

    @Test
    void approve_成功_設定ACTIVE並記錄審核者與選填備註() {
        Employee employee = pendingEmployee();
        Employee reviewer = reviewer();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(employees.findById(REVIEWER_ID)).thenReturn(Optional.of(reviewer));

        service.approve(EMPLOYEE_ID, REVIEWER_ID, "附條件核准");

        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(employee.getReviewedBy()).isEqualTo(reviewer);
        assertThat(employee.getReviewedAt()).isNotNull();
        assertThat(employee.getReviewNote()).isEqualTo("附條件核准");
    }

    @Test
    void approve_備註可為null() {
        Employee employee = pendingEmployee();
        Employee reviewer = reviewer();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(employees.findById(REVIEWER_ID)).thenReturn(Optional.of(reviewer));

        service.approve(EMPLOYEE_ID, REVIEWER_ID, null);

        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(employee.getReviewNote()).isNull();
    }

    @Test
    void approve_找不到申請時丟例外() {
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(EMPLOYEE_ID, REVIEWER_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(employees, never()).findById(eq(REVIEWER_ID));
    }

    @Test
    void approve_申請已被刪除時丟例外() {
        Employee employee = pendingEmployee();
        employee.softDelete();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.approve(EMPLOYEE_ID, REVIEWER_ID, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void approve_非待審核狀態時丟例外() {
        Employee employee = pendingEmployee();
        employee.setStatus(EmployeeStatus.ACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.approve(EMPLOYEE_ID, REVIEWER_ID, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void approve_找不到審核者時丟例外() {
        Employee employee = pendingEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(employees.findById(REVIEWER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(EMPLOYEE_ID, REVIEWER_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.PENDING_APPROVAL); // 還沒被改動
    }

    // === reject() ===============================================================

    @Test
    void reject_成功_設定REJECTED並記錄理由() {
        Employee employee = pendingEmployee();
        Employee reviewer = reviewer();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(employees.findById(REVIEWER_ID)).thenReturn(Optional.of(reviewer));

        service.reject(EMPLOYEE_ID, REVIEWER_ID, "資料不齊全");

        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.REJECTED);
        assertThat(employee.getReviewedBy()).isEqualTo(reviewer);
        assertThat(employee.getReviewedAt()).isNotNull();
        assertThat(employee.getReviewNote()).isEqualTo("資料不齊全");
    }

    @Test
    void reject_找不到申請時丟例外() {
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reject(EMPLOYEE_ID, REVIEWER_ID, "理由"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reject_申請已被刪除時丟例外() {
        Employee employee = pendingEmployee();
        employee.softDelete();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.reject(EMPLOYEE_ID, REVIEWER_ID, "理由"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reject_非待審核狀態時丟例外() {
        Employee employee = pendingEmployee();
        employee.setStatus(EmployeeStatus.REJECTED);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.reject(EMPLOYEE_ID, REVIEWER_ID, "理由"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reject_找不到審核者時丟例外() {
        Employee employee = pendingEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(employees.findById(REVIEWER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reject(EMPLOYEE_ID, REVIEWER_ID, "理由"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.PENDING_APPROVAL); // 還沒被改動
    }
}
