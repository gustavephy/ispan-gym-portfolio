package tw.com.ispan.gym.service.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import tw.com.ispan.gym.dto.employee.AdminEmployeeResponse;
import tw.com.ispan.gym.dto.employee.AdminEmployeeRoleUpdateRequest;
import tw.com.ispan.gym.dto.employee.AdminEmployeeSummaryResponse;
import tw.com.ispan.gym.dto.employee.AdminEmployeeUpdateRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.InvalidRequestException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 純 Mockito 單元測試（比照 AdminMemberServiceTest），不連資料庫。
 * findEmployeesForAdmin()／findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc() 已 mock：
 * 這裡只驗證 Service 有沒有把參數正確傳給 Repository、狀態機與最後一位 ADMIN 保護是否正確，
 * 驗不到「查得到」——那是 JPQL 本身的正確性，留給真實 DB E2E。
 */
class AdminEmployeeServiceTest {

    private static final Integer EMPLOYEE_ID = 3;

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final TrainerRepository trainers = mock(TrainerRepository.class);
    private final AdminEmployeeService service = new AdminEmployeeService(employees, trainers);

    private Employee employeeOf(EmployeeRole role, EmployeeStatus status) {
        Account account = new Account();
        account.setId(5);
        account.setEmail("employee@example.com");

        Employee employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        employee.setAccount(account);
        employee.setFirstName("小美");
        employee.setLastName("王");
        employee.setPhone("0922222222");
        employee.setRole(role);
        employee.setStatus(status);
        employee.setHireDate(LocalDate.of(2020, 1, 1));
        employee.setCreatedAt(LocalDateTime.now());
        return employee;
    }

    private AdminEmployeeUpdateRequest validUpdateRequest() {
        AdminEmployeeUpdateRequest request = new AdminEmployeeUpdateRequest();
        request.setFirstName("小明");
        request.setLastName("林");
        request.setPhone("0933333333");
        request.setHireDate(LocalDate.of(2021, 6, 1));
        return request;
    }

    private AdminEmployeeRoleUpdateRequest roleRequest(EmployeeRole role) {
        AdminEmployeeRoleUpdateRequest request = new AdminEmployeeRoleUpdateRequest();
        request.setRole(role);
        return request;
    }

    private void noOtherActiveAdmins() {
        when(employees.findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE))
                .thenReturn(List.of());
    }

    // === list()：分頁參數驗證 ====================================================

    @Test
    void list_page小於0時丟InvalidRequest_且不呼叫Repository() {
        assertThatThrownBy(() -> service.list(null, null, null, -1, 10, null, null))
                .isInstanceOf(InvalidRequestException.class);
        verify(employees, never()).findEmployeesForAdmin(any(), any(), any(), any());
    }

    @Test
    void list_size為0或超過上限時丟InvalidRequest_且不呼叫Repository() {
        assertThatThrownBy(() -> service.list(null, null, null, 0, 0, null, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, null, 0, 101, null, null))
                .isInstanceOf(InvalidRequestException.class);
        verify(employees, never()).findEmployeesForAdmin(any(), any(), any(), any());
    }

    @Test
    void list_size為1或100時合法() {
        when(employees.findEmployeesForAdmin(any(), any(), any(), any())).thenReturn(Page.empty());

        assertThatCode(() -> service.list(null, null, null, 0, 1, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> service.list(null, null, null, 0, 100, null, null)).doesNotThrowAnyException();
    }

    @Test
    void list_page與size正確組裝進Pageable() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(employees.findEmployeesForAdmin(any(), any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, null, 2, 25, null, null);

        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    // === list()：keyword／status／role 傳遞 ========================================

    @Test
    void list_空白keyword正規化為null() {
        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        when(employees.findEmployeesForAdmin(keywordCaptor.capture(), any(), any(), any())).thenReturn(Page.empty());

        service.list("   ", null, null, 0, 10, null, null);

        assertThat(keywordCaptor.getValue()).isNull();
    }

    @Test
    void list_關鍵字trim後與狀態角色原樣傳給Repository() {
        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<EmployeeStatus> statusCaptor = ArgumentCaptor.forClass(EmployeeStatus.class);
        ArgumentCaptor<EmployeeRole> roleCaptor = ArgumentCaptor.forClass(EmployeeRole.class);
        when(employees.findEmployeesForAdmin(keywordCaptor.capture(), statusCaptor.capture(), roleCaptor.capture(),
                any())).thenReturn(Page.empty());

        service.list("  small@example.com  ", EmployeeStatus.ACTIVE, EmployeeRole.ADMIN, 0, 10, null, null);

        assertThat(keywordCaptor.getValue()).isEqualTo("small@example.com");
        assertThat(statusCaptor.getValue()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(roleCaptor.getValue()).isEqualTo(EmployeeRole.ADMIN);
    }

    // === list()：預設排序＋映射 ====================================================

    @Test
    void list_無條件時使用預設排序且正確映射為摘要DTO() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        Page<Employee> page = new PageImpl<>(List.of(employee));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(employees.findEmployeesForAdmin(isNull(), isNull(), isNull(), pageableCaptor.capture()))
                .thenReturn(page);

        Page<AdminEmployeeSummaryResponse> result = service.list(null, null, null, 0, 10, null, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).employeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(result.getContent().get(0).displayName()).isEqualTo("王小美");
        assertThat(pageableCaptor.getValue().getSort()).containsExactly(Sort.Order.asc("id"));
    }

    // === list()：排序白名單與次排序 ================================================

    @Test
    void list_sort為null空白或非法值時回退id() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(employees.findEmployeesForAdmin(any(), any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, null, 0, 10, null, null);
        service.list(null, null, null, 0, 10, "  ", null);
        service.list(null, null, null, 0, 10, "unknownField", null);

        for (Pageable pageable : pageableCaptor.getAllValues()) {
            assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("id"));
        }
    }

    @Test
    void list_sort為name時展開為姓名加id次排序() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(employees.findEmployeesForAdmin(any(), any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, null, 0, 10, "name", null);

        assertThat(pageableCaptor.getValue().getSort()).containsExactly(
                Sort.Order.asc("lastName"),
                Sort.Order.asc("firstName"),
                Sort.Order.asc("id"));
    }

    @Test
    void list_sort為name且direction為desc時姓名降冪但id次排序仍是升冪() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(employees.findEmployeesForAdmin(any(), any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, null, 0, 10, "name", "desc");

        assertThat(pageableCaptor.getValue().getSort()).containsExactly(
                Sort.Order.desc("lastName"),
                Sort.Order.desc("firstName"),
                Sort.Order.asc("id"));
    }

    @Test
    void list_sort為hireDate時附加id次排序() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(employees.findEmployeesForAdmin(any(), any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, null, 0, 10, "hireDate", null);

        assertThat(pageableCaptor.getValue().getSort()).containsExactly(
                Sort.Order.asc("hireDate"),
                Sort.Order.asc("id"));
    }

    @Test
    void list_sort為createdAt時附加id次排序() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(employees.findEmployeesForAdmin(any(), any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, null, 0, 10, "createdAt", null);

        assertThat(pageableCaptor.getValue().getSort()).containsExactly(
                Sort.Order.asc("createdAt"),
                Sort.Order.asc("id"));
    }

    @Test
    void list_direction為desc或DESC不分大小寫皆生效() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(employees.findEmployeesForAdmin(any(), any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, null, 0, 10, "id", "desc");
        service.list(null, null, null, 0, 10, "id", "DESC");

        for (Pageable pageable : pageableCaptor.getAllValues()) {
            assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("id"));
        }
    }

    @Test
    void list_direction為null或非法值時回退asc() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(employees.findEmployeesForAdmin(any(), any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, null, 0, 10, "id", null);
        service.list(null, null, null, 0, 10, "id", "sideways");

        for (Pageable pageable : pageableCaptor.getAllValues()) {
            assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("id"));
        }
    }

    // === getById() ==================================================================

    @Test
    void getById_成功回傳完整資料() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        AdminEmployeeResponse response = service.getById(EMPLOYEE_ID);

        assertThat(response.employeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(response.email()).isEqualTo("employee@example.com");
        assertThat(response.isTrainer()).isFalse();
    }

    @Test
    void getById_具有效Trainer身分時isTrainer為true() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(new Trainer()));

        AdminEmployeeResponse response = service.getById(EMPLOYEE_ID);

        assertThat(response.isTrainer()).isTrue();
    }

    @Test
    void getById_找不到時丟ResourceNotFound() {
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(EMPLOYEE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_已軟刪除時丟ResourceNotFound() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        employee.setDeletedAt(LocalDateTime.now());
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.getById(EMPLOYEE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === update() ====================================================================

    @Test
    void update_成功寫入四個欄位() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        AdminEmployeeResponse response = service.update(EMPLOYEE_ID, validUpdateRequest());

        assertThat(response.firstName()).isEqualTo("小明");
        assertThat(response.lastName()).isEqualTo("林");
        assertThat(response.phone()).isEqualTo("0933333333");
        assertThat(response.hireDate()).isEqualTo(LocalDate.of(2021, 6, 1));
    }

    @Test
    void update_不影響role與status() {
        Employee employee = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        service.update(EMPLOYEE_ID, validUpdateRequest());

        assertThat(employee.getRole()).isEqualTo(EmployeeRole.ADMIN);
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void update_使用悲觀鎖_不呼叫普通findById() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        service.update(EMPLOYEE_ID, validUpdateRequest());

        verify(employees).findByIdForUpdate(EMPLOYEE_ID);
        verify(employees, never()).findById(EMPLOYEE_ID);
    }

    @Test
    void update_找不到時丟ResourceNotFound() {
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(EMPLOYEE_ID, validUpdateRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_已軟刪除時丟ResourceNotFound() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        employee.setDeletedAt(LocalDateTime.now());
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.update(EMPLOYEE_ID, validUpdateRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === update()：Trainer 姓名快照同步（2026-08-19 補丁） ===========================

    @Test
    void update_沒有Trainer時一般更新不受影響() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> service.update(EMPLOYEE_ID, validUpdateRequest())).doesNotThrowAnyException();
    }

    @Test
    void update_ACTIVETrainer姓名被同步() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        Trainer trainer = new Trainer();
        trainer.setStatus(TrainerStatus.ACTIVE);
        trainer.setFirstName("小美");
        trainer.setLastName("王");
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        service.update(EMPLOYEE_ID, validUpdateRequest());

        assertThat(trainer.getFirstName()).isEqualTo("小明");
        assertThat(trainer.getLastName()).isEqualTo("林");
    }

    @Test
    void update_UNAVAILABLETrainer姓名也被同步() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        Trainer trainer = new Trainer();
        trainer.setStatus(TrainerStatus.UNAVAILABLE);
        trainer.setFirstName("小美");
        trainer.setLastName("王");
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        service.update(EMPLOYEE_ID, validUpdateRequest());

        assertThat(trainer.getFirstName()).isEqualTo("小明");
        assertThat(trainer.getLastName()).isEqualTo("林");
    }

    /** 已軟刪除的 Trainer 也要同步——之後恢復（revive-not-recreate）時才不會看到停用期間變舊的姓名 */
    @Test
    void update_已軟刪除的Trainer姓名仍被同步() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        Trainer trainer = new Trainer();
        trainer.setDeletedAt(LocalDateTime.now());
        trainer.setFirstName("小美");
        trainer.setLastName("王");
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        service.update(EMPLOYEE_ID, validUpdateRequest());

        assertThat(trainer.getFirstName()).isEqualTo("小明");
        assertThat(trainer.getLastName()).isEqualTo("林");
    }

    @Test
    void update_不同步Trainer的email() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        Trainer trainer = new Trainer();
        trainer.setEmail("old-trainer-email@example.com");
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        service.update(EMPLOYEE_ID, validUpdateRequest());

        assertThat(trainer.getEmail()).isEqualTo("old-trainer-email@example.com");
    }

    @Test
    void update_只改電話與到職日_姓名不變時Trainer同步結果仍正確() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        Trainer trainer = new Trainer();
        trainer.setFirstName("小美");
        trainer.setLastName("王");
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        AdminEmployeeUpdateRequest request = validUpdateRequest();
        request.setFirstName("小美");
        request.setLastName("王");

        service.update(EMPLOYEE_ID, request);

        assertThat(trainer.getFirstName()).isEqualTo("小美");
        assertThat(trainer.getLastName()).isEqualTo("王");
    }

    // === changeRole()：升級 EMPLOYEE → ADMIN =========================================

    @Test
    void changeRole_升級成功_不查有效ADMIN集合() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        AdminEmployeeResponse response = service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.ADMIN));

        assertThat(response.role()).isEqualTo(EmployeeRole.ADMIN);
        verify(employees, never()).findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc(any(), any());
    }

    @Test
    void changeRole_已經是ADMIN時升級丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.ADMIN)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void changeRole_升級非ACTIVE員工時丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.INACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.ADMIN)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void changeRole_升級找不到時丟ResourceNotFound() {
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.ADMIN)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === changeRole()：降級 ADMIN → EMPLOYEE =========================================

    @Test
    void changeRole_降級成功_當有其他有效ADMIN時() {
        Employee employee = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        Employee otherAdmin = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        otherAdmin.setEmployeeId(99);
        when(employees.findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE))
                .thenReturn(List.of(employee, otherAdmin));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        AdminEmployeeResponse response = service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.EMPLOYEE));

        assertThat(response.role()).isEqualTo(EmployeeRole.EMPLOYEE);
    }

    @Test
    void changeRole_降級最後一位有效ADMIN時丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        when(employees.findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE))
                .thenReturn(List.of(employee));

        assertThatThrownBy(() -> service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.EMPLOYEE)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void changeRole_已經是EMPLOYEE時降級丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.EMPLOYEE)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void changeRole_降級非ACTIVE的ADMIN時丟BusinessRule() {
        // 目標不在有效 ADMIN 集合中（因為非 ACTIVE），回退用一般鎖定查詢取得真實狀態
        Employee employee = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.INACTIVE);
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.EMPLOYEE)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void changeRole_降級找不到時丟ResourceNotFound() {
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.EMPLOYEE)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void changeRole_降級已軟刪除時丟ResourceNotFound() {
        Employee employee = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        employee.setDeletedAt(LocalDateTime.now());
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.EMPLOYEE)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** ADMIN 可以對自己降級，只要不是最後一位——這裡驗證「目標=呼叫者」這件事本身不受特殊限制 */
    @Test
    void changeRole_ADMIN可以降級自己_只要不是最後一位() {
        Employee self = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        Employee otherAdmin = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        otherAdmin.setEmployeeId(99);
        when(employees.findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE))
                .thenReturn(List.of(self, otherAdmin));

        assertThatCode(() -> service.changeRole(EMPLOYEE_ID, roleRequest(EmployeeRole.EMPLOYEE)))
                .doesNotThrowAnyException();
    }

    // === suspend() ====================================================================

    @Test
    void suspend_ACTIVE一般員工轉為INACTIVE() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        service.suspend(EMPLOYEE_ID);

        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
    }

    @Test
    void suspend_ACTIVE的ADMIN當還有其他有效ADMIN時可停用() {
        Employee employee = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        Employee otherAdmin = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        otherAdmin.setEmployeeId(99);
        when(employees.findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE))
                .thenReturn(List.of(employee, otherAdmin));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        service.suspend(EMPLOYEE_ID);

        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
    }

    @Test
    void suspend_最後一位有效ADMIN時丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        when(employees.findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE))
                .thenReturn(List.of(employee));

        assertThatThrownBy(() -> service.suspend(EMPLOYEE_ID))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    /** ADMIN 可以停用自己，只要不是最後一位 */
    @Test
    void suspend_ADMIN可以停用自己_只要不是最後一位() {
        Employee self = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        Employee otherAdmin = employeeOf(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        otherAdmin.setEmployeeId(99);
        when(employees.findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc(EmployeeRole.ADMIN, EmployeeStatus.ACTIVE))
                .thenReturn(List.of(self, otherAdmin));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> service.suspend(EMPLOYEE_ID)).doesNotThrowAnyException();
        assertThat(self.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
    }

    @Test
    void suspend_已是INACTIVE時丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.INACTIVE);
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.suspend(EMPLOYEE_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void suspend_PENDING_APPROVAL時丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.PENDING_APPROVAL);
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.suspend(EMPLOYEE_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void suspend_REJECTED時丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.REJECTED);
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.suspend(EMPLOYEE_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void suspend_找不到或已軟刪除時丟ResourceNotFound() {
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.suspend(EMPLOYEE_ID)).isInstanceOf(ResourceNotFoundException.class);

        Employee deleted = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        deleted.setDeletedAt(LocalDateTime.now());
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> service.suspend(EMPLOYEE_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    // === suspend()：Trainer 阻擋 ======================================================

    @Test
    void suspend_具有效ACTIVETrainer身分時丟BusinessRule_且不變更狀態() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        Trainer trainer = new Trainer();
        trainer.setStatus(TrainerStatus.ACTIVE);
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.suspend(EMPLOYEE_ID))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void suspend_具有效UNAVAILABLETrainer身分時仍丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        Trainer trainer = new Trainer();
        trainer.setStatus(TrainerStatus.UNAVAILABLE);
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.suspend(EMPLOYEE_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void suspend_已軟刪除的Trainer不阻擋() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        noOtherActiveAdmins();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        Trainer trainer = new Trainer();
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        service.suspend(EMPLOYEE_ID);

        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
    }

    // === activate() ====================================================================

    @Test
    void activate_INACTIVE轉為ACTIVE() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.INACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        service.activate(EMPLOYEE_ID);

        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void activate_已是ACTIVE時丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.activate(EMPLOYEE_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void activate_PENDING_APPROVAL時丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.PENDING_APPROVAL);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.activate(EMPLOYEE_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void activate_REJECTED時丟BusinessRule() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.REJECTED);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.activate(EMPLOYEE_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void activate_找不到或已軟刪除時丟ResourceNotFound() {
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate(EMPLOYEE_ID)).isInstanceOf(ResourceNotFoundException.class);

        Employee deleted = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.INACTIVE);
        deleted.setDeletedAt(LocalDateTime.now());
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> service.activate(EMPLOYEE_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void activate_不需要查詢有效ADMIN集合() {
        Employee employee = employeeOf(EmployeeRole.EMPLOYEE, EmployeeStatus.INACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        service.activate(EMPLOYEE_ID);

        verify(employees, never()).findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc(any(), any());
    }
}
