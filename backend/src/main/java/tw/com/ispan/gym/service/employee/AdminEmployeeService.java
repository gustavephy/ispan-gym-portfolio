package tw.com.ispan.gym.service.employee;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.employee.AdminEmployeeResponse;
import tw.com.ispan.gym.dto.employee.AdminEmployeeRoleUpdateRequest;
import tw.com.ispan.gym.dto.employee.AdminEmployeeSummaryResponse;
import tw.com.ispan.gym.dto.employee.AdminEmployeeUpdateRequest;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.InvalidRequestException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * ADMIN 員工管理：列表／單筆查詢／代改個資／角色調整／停用／恢復。不動既有的員工申請、
 * 員工審核（EmployeeReviewService，2026-08-31 起同為 ADMIN-only，但屬審核流程、
 * 與這裡的人事管理是不同動作）、拒絕後重新申請。
 *
 * <p>update()／changeRole()／suspend()／activate() 皆以悲觀鎖讀取，理由同
 * AdminMemberService：避免同一筆資料被多個操作用記憶體裡的舊值互相覆蓋。降級與停用
 * 這兩個「可能讓有效 ADMIN 數量歸零」的動作，額外先鎖定整組有效 ADMIN（依 id ASC，
 * 見 EmployeeRepository.findByRoleAndStatusAndDeletedAtIsNullOrderByIdAsc 的說明），
 * 避免兩個併發請求各自用鎖定前讀到的數量通過檢查、一起把系統的 ADMIN 降到零。
 */
@Service
@Transactional(readOnly = true)
public class AdminEmployeeService {

    private static final Set<String> ALLOWED_SORTS = Set.of("id", "name", "hireDate", "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final EmployeeRepository employees;
    private final TrainerRepository trainers;

    public AdminEmployeeService(EmployeeRepository employees, TrainerRepository trainers) {
        this.employees = employees;
        this.trainers = trainers;
    }

    public Page<AdminEmployeeSummaryResponse> list(String keyword, EmployeeStatus status, EmployeeRole role,
            int page, int size, String sort, String direction) {
        String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Pageable pageable = buildPageable(page, size, sort, direction);
        return employees.findEmployeesForAdmin(normalizedKeyword, status, role, pageable)
                .map(AdminEmployeeSummaryResponse::from);
    }

    public AdminEmployeeResponse getById(Integer employeeId) {
        Employee employee = employees.findById(employeeId)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此員工"));
        return AdminEmployeeResponse.from(employee, isTrainer(employeeId));
    }

    @Transactional
    public AdminEmployeeResponse update(Integer employeeId, AdminEmployeeUpdateRequest request) {
        Employee employee = lockOrThrow(employeeId);

        employee.setFirstName(request.getFirstName());
        employee.setLastName(request.getLastName());
        employee.setPhone(request.getPhone());
        employee.setHireDate(request.getHireDate());

        // Trainer.firstName／lastName 是建立當下自 Employee 複製的快照（2026-08-19 引入，
        // 見 SchemaFixupRunner.addTrainerProfileColumns()）。SchemaFixupRunner 目前確實有
        // 一段會在每次啟動時把所有不同步的 Trainer 姓名重新對齊 Employee（見
        // fixReversedTestAccountNames() 裡不限帳號範圍的 UPDATE ... WHERE first_name<>...
        // OR last_name<>...），但那只在應用「重啟」時才跑一次；正式環境的即時修改（本方法）
        // 之後、下次部署或重啟之前，這段時間內兩邊姓名仍會不一致，所以這裡的同步仍然必要，
        // 用來即時維持一致，不是被那個機制取代。涵蓋已軟刪除的 Trainer（revive-not-recreate，
        // 恢復後才不會看到舊姓名），不同步 email——Account email 修改不在本階段範圍內。
        trainers.findByEmployeeId(employeeId).ifPresent(trainer -> {
            trainer.setFirstName(employee.getFirstName());
            trainer.setLastName(employee.getLastName());
        });

        return AdminEmployeeResponse.from(employee, isTrainer(employeeId));
    }

    @Transactional
    public AdminEmployeeResponse changeRole(Integer employeeId, AdminEmployeeRoleUpdateRequest request) {
        if (request.getRole() == EmployeeRole.EMPLOYEE) {
            return demoteToEmployee(employeeId);
        }
        return promoteToAdmin(employeeId);
    }

    private AdminEmployeeResponse demoteToEmployee(Integer employeeId) {
        // 降級一定先鎖整組有效 ADMIN——目標若真的是可降級的 ACTIVE ADMIN，必然在這個集合裡；
        // 若不在（不是 ADMIN、不是 ACTIVE，或不存在），回退成一般鎖定查詢以取得正確的錯誤訊息。
        List<Employee> activeAdmins = employees.findByRoleAndStatusAndDeletedAtIsNullOrderByIdAsc(
                EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        Employee employee = activeAdmins.stream()
                .filter(e -> e.getId().equals(employeeId))
                .findFirst()
                .orElseGet(() -> lockOrThrow(employeeId));

        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessRuleException("此員工目前不是在職狀態，無法調整角色");
        }
        if (employee.getRole() == EmployeeRole.EMPLOYEE) {
            throw new BusinessRuleException("此員工已經是指定角色");
        }
        if (activeAdmins.size() <= 1) {
            throw new BusinessRuleException("必須至少保留一位有效管理員，無法降級最後一位");
        }

        employee.setRole(EmployeeRole.EMPLOYEE);
        return AdminEmployeeResponse.from(employee, isTrainer(employeeId));
    }

    private AdminEmployeeResponse promoteToAdmin(Integer employeeId) {
        // 升級不會減少有效 ADMIN 數量，只鎖目標本人即可
        Employee employee = lockOrThrow(employeeId);

        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessRuleException("此員工目前不是在職狀態，無法調整角色");
        }
        if (employee.getRole() == EmployeeRole.ADMIN) {
            throw new BusinessRuleException("此員工已經是指定角色");
        }

        employee.setRole(EmployeeRole.ADMIN);
        return AdminEmployeeResponse.from(employee, isTrainer(employeeId));
    }

    @Transactional
    public void suspend(Integer employeeId) {
        List<Employee> activeAdmins = employees.findByRoleAndStatusAndDeletedAtIsNullOrderByIdAsc(
                EmployeeRole.ADMIN, EmployeeStatus.ACTIVE);
        Employee employee = activeAdmins.stream()
                .filter(e -> e.getId().equals(employeeId))
                .findFirst()
                .orElseGet(() -> lockOrThrow(employeeId));

        switch (employee.getStatus()) {
            case INACTIVE -> throw new BusinessRuleException("此員工已是停用狀態");
            case PENDING_APPROVAL -> throw new BusinessRuleException("此員工尚未通過審核，無法停用");
            case REJECTED -> throw new BusinessRuleException("此員工申請已被拒絕，無法停用");
            case ACTIVE -> { /* 繼續下方檢查 */ }
        }

        if (employee.getRole() == EmployeeRole.ADMIN && activeAdmins.size() <= 1) {
            throw new BusinessRuleException("必須至少保留一位有效管理員，無法停用最後一位");
        }

        trainers.findByEmployeeId(employeeId)
                .filter(t -> t.getDeletedAt() == null)
                .ifPresent(t -> {
                    throw new BusinessRuleException("此員工仍具有效教練身分，須待 Trainer 管理階段釋出教練身分後才能停用");
                });

        employee.setStatus(EmployeeStatus.INACTIVE);
    }

    @Transactional
    public void activate(Integer employeeId) {
        Employee employee = lockOrThrow(employeeId);
        switch (employee.getStatus()) {
            case ACTIVE -> throw new BusinessRuleException("此員工已是啟用狀態");
            case PENDING_APPROVAL -> throw new BusinessRuleException("此員工尚未通過審核，請由審核流程處理");
            case REJECTED -> throw new BusinessRuleException("此員工申請已被拒絕，請走重新申請流程");
            case INACTIVE -> employee.setStatus(EmployeeStatus.ACTIVE);
        }
    }

    private boolean isTrainer(Integer employeeId) {
        return trainers.findByEmployeeId(employeeId).filter(t -> t.getDeletedAt() == null).isPresent();
    }

    private Employee lockOrThrow(Integer employeeId) {
        return employees.findByIdForUpdate(employeeId)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此員工"));
    }

    private Pageable buildPageable(int page, int size, String sort, String direction) {
        if (page < 0) {
            throw new InvalidRequestException("頁碼不可小於 0");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("每頁筆數必須介於 1 到 " + MAX_PAGE_SIZE);
        }

        String normalizedSort = sort == null ? "id" : sort.trim();
        String effectiveSort = ALLOWED_SORTS.contains(normalizedSort) ? normalizedSort : "id";

        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        List<Sort.Order> orders = new ArrayList<>();
        switch (effectiveSort) {
            case "name" -> {
                orders.add(new Sort.Order(dir, "lastName"));
                orders.add(new Sort.Order(dir, "firstName"));
                orders.add(new Sort.Order(Sort.Direction.ASC, "id"));
            }
            case "hireDate" -> {
                orders.add(new Sort.Order(dir, "hireDate"));
                orders.add(new Sort.Order(Sort.Direction.ASC, "id"));
            }
            case "createdAt" -> {
                orders.add(new Sort.Order(dir, "createdAt"));
                orders.add(new Sort.Order(Sort.Direction.ASC, "id"));
            }
            default -> orders.add(new Sort.Order(dir, "id"));
        }
        return PageRequest.of(page, size, Sort.by(orders));
    }
}
