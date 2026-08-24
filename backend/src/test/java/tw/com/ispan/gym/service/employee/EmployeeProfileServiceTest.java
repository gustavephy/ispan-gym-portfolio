package tw.com.ispan.gym.service.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.employee.EmployeeProfileResponse;
import tw.com.ispan.gym.dto.employee.EmployeeProfileUpdateRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 純 Mockito 單元測試（比照 MemberProfileServiceTest），不連資料庫。
 * accountId／employeeId 一律由呼叫端直接傳入——Service 不注入 CurrentUserProvider，
 * 「目前登入者是誰」是 Controller 的職責，不在這裡測。
 */
class EmployeeProfileServiceTest {

    private static final Integer ACCOUNT_ID = 5;
    private static final Integer EMPLOYEE_ID = 3;

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final TrainerRepository trainers = mock(TrainerRepository.class);
    private final EmployeeProfileService service = new EmployeeProfileService(employees, trainers);

    private Employee employeeOf(EmployeeStatus status) {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("employee@example.com");

        Employee employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        employee.setAccount(account);
        employee.setFirstName("小美");
        employee.setLastName("王");
        employee.setPhone("0922222222");
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(status);
        employee.setHireDate(LocalDate.of(2020, 1, 1));
        employee.setCreatedAt(LocalDateTime.now());
        return employee;
    }

    private EmployeeProfileUpdateRequest validRequest() {
        EmployeeProfileUpdateRequest request = new EmployeeProfileUpdateRequest();
        request.setPhone("0933333333");
        return request;
    }

    // === getMyProfile() ==========================================================

    @Test
    void getMyProfile_ACTIVE員工查自己_回傳完整資料() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        EmployeeProfileResponse response = service.getMyProfile(EMPLOYEE_ID);

        assertThat(response.employeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(response.email()).isEqualTo("employee@example.com");
        assertThat(response.status()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(response.isTrainer()).isFalse();
    }

    @Test
    void getMyProfile_具有效Trainer身分時isTrainer為true() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(new Trainer()));

        EmployeeProfileResponse response = service.getMyProfile(EMPLOYEE_ID);

        assertThat(response.isTrainer()).isTrue();
    }

    @Test
    void getMyProfile_已軟刪除的Trainer不算有效_isTrainer為false() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        Trainer trainer = new Trainer();
        trainer.setDeletedAt(LocalDateTime.now());
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        EmployeeProfileResponse response = service.getMyProfile(EMPLOYEE_ID);

        assertThat(response.isTrainer()).isFalse();
    }

    /** INACTIVE 員工不能自助查詢——跟 Member 的 SUSPENDED 仍可查自己不同，這是刻意的規格差異 */
    @Test
    void getMyProfile_INACTIVE員工查自己_丟ForbiddenAccess() {
        Employee employee = employeeOf(EmployeeStatus.INACTIVE);
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.getMyProfile(EMPLOYEE_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void getMyProfile_PENDING_APPROVAL員工查自己_丟ForbiddenAccess() {
        Employee employee = employeeOf(EmployeeStatus.PENDING_APPROVAL);
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.getMyProfile(EMPLOYEE_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void getMyProfile_REJECTED員工查自己_丟ForbiddenAccess() {
        Employee employee = employeeOf(EmployeeStatus.REJECTED);
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.getMyProfile(EMPLOYEE_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void getMyProfile_找不到員工資料時丟ForbiddenAccess() {
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyProfile(EMPLOYEE_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void getMyProfile_已軟刪除的員工_丟ForbiddenAccess() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        employee.setDeletedAt(LocalDateTime.now());
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.getMyProfile(EMPLOYEE_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    // === updateMyProfile() =======================================================

    /**
     * 必須經由 account id 悲觀鎖，不能先用任何未鎖定查詢載入同一個 Employee
     * （identity map 陷阱：先讀過的舊物件即使之後鎖定命中同一列，Hibernate 也不會用新結果覆蓋欄位值）。
     */
    @Test
    void updateMyProfile_以account_id悲觀鎖讀取_不預先載入Employee() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        when(employees.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        service.updateMyProfile(ACCOUNT_ID, validRequest());

        verify(employees).findByAccountIdForUpdate(ACCOUNT_ID);
        verify(employees, never()).findByAccountId(any());
        verify(employees, never()).findByIdForUpdate(any());
        verify(employees, never()).findById(any());
    }

    @Test
    void updateMyProfile_成功寫入phone_不影響姓名() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        when(employees.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        EmployeeProfileResponse response = service.updateMyProfile(ACCOUNT_ID, validRequest());

        assertThat(response.phone()).isEqualTo("0933333333");
        assertThat(response.firstName()).isEqualTo("小美");
        assertThat(response.lastName()).isEqualTo("王");
        assertThat(employee.getFirstName()).isEqualTo("小美");
        assertThat(employee.getLastName()).isEqualTo("王");
    }

    @Test
    void updateMyProfile_鎖定後讀到INACTIVE時仍拒絕() {
        Employee employee = employeeOf(EmployeeStatus.INACTIVE);
        when(employees.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void updateMyProfile_鎖定後讀到PENDING_APPROVAL時仍拒絕() {
        Employee employee = employeeOf(EmployeeStatus.PENDING_APPROVAL);
        when(employees.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void updateMyProfile_鎖定後讀到已軟刪除時仍拒絕() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        employee.setDeletedAt(LocalDateTime.now());
        when(employees.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void updateMyProfile_找不到員工資料時丟ForbiddenAccess() {
        when(employees.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void updateMyProfile_不會更動role或status() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        when(employees.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        service.updateMyProfile(ACCOUNT_ID, validRequest());

        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(employee.getRole()).isEqualTo(EmployeeRole.EMPLOYEE);
    }
}
