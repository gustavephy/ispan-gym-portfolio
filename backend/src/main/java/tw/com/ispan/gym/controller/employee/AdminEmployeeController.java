package tw.com.ispan.gym.controller.employee;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.employee.AdminEmployeeResponse;
import tw.com.ispan.gym.dto.employee.AdminEmployeeRoleUpdateRequest;
import tw.com.ispan.gym.dto.employee.AdminEmployeeSummaryResponse;
import tw.com.ispan.gym.dto.employee.AdminEmployeeUpdateRequest;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.service.employee.AdminEmployeeService;

/**
 * ADMIN 員工管理：列表／單筆查詢／代改個資／角色調整／停用／恢復。不動既有的員工申請
 * （EmployeeRegisterController）、員工審核（EmployeeReviewController，2026-08-31 起
 * ADMIN-only，屬審核流程、與這裡的人事管理是不同動作）、拒絕後重新申請。
 *
 * <p>/api/admin/** 已由正式 SecurityConfig 的 URL matcher 使用 hasRole("ADMIN") 保護，
 * 這裡的方法層級 @PreAuthorize 是第二層防護，比照 AdminMemberController 既有風格——
 * URL 層會先攔截非 ADMIN，EndpointAuthorizationTest 用真牌 SecurityConfig 測這幾個
 * 端點時只能證明 URL 層規則生效，方法層 @PreAuthorize 本身是否正確由獨立的
 * AdminEmployeeMethodSecurityTest（刻意放寬 URL 層）驗證。
 *
 * <p>page／size／sort／direction 的合法性驗證與排序白名單展開，全部交給
 * AdminEmployeeService，這裡不重複判斷。EmployeeStatus／EmployeeRole 轉換失敗
 * （前端傳入不存在的值）由既有的 MethodArgumentTypeMismatchException handler 回 400，
 * 不需要額外處理。
 */
@RestController
@RequestMapping("/api/admin/employees")
public class AdminEmployeeController {

    private final AdminEmployeeService adminEmployeeService;

    public AdminEmployeeController(AdminEmployeeService adminEmployeeService) {
        this.adminEmployeeService = adminEmployeeService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Page<AdminEmployeeSummaryResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) EmployeeStatus status,
            @RequestParam(required = false) EmployeeRole role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return ResponseEntity.ok(adminEmployeeService.list(keyword, status, role, page, size, sort, direction));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{employeeId}")
    public AdminEmployeeResponse getById(@PathVariable Integer employeeId) {
        return adminEmployeeService.getById(employeeId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{employeeId}")
    public AdminEmployeeResponse update(@PathVariable Integer employeeId,
            @Valid @RequestBody AdminEmployeeUpdateRequest request) {
        return adminEmployeeService.update(employeeId, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{employeeId}/role")
    public AdminEmployeeResponse changeRole(@PathVariable Integer employeeId,
            @Valid @RequestBody AdminEmployeeRoleUpdateRequest request) {
        return adminEmployeeService.changeRole(employeeId, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{employeeId}/suspend")
    public void suspend(@PathVariable Integer employeeId) {
        adminEmployeeService.suspend(employeeId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{employeeId}/activate")
    public void activate(@PathVariable Integer employeeId) {
        adminEmployeeService.activate(employeeId);
    }
}
