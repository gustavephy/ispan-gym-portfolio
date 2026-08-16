package tw.com.ispan.gym.service.employee;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.employee.PendingEmployeeResponse;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;

/**
 * 員工審核：任何 ACTIVE 的員工都能核准或拒絕其他人的申請，不限管理者
 * （目前規模小、還沒有正式分層；未來要限縮時只需把 SecurityConfig／Controller 的
 * hasRole("EMPLOYEE") 改成 hasRole("ADMIN")，不需要動這裡的邏輯）。
 */
@Service
@Transactional(readOnly = true)
public class EmployeeReviewService {

    private final EmployeeRepository employees;

    public EmployeeReviewService(EmployeeRepository employees) {
        this.employees = employees;
    }

    public List<PendingEmployeeResponse> listPending() {
        return employees.findByStatusAndDeletedAtIsNull(EmployeeStatus.PENDING_APPROVAL).stream()
                .map(PendingEmployeeResponse::from)
                .toList();
    }

    @Transactional
    public void approve(Integer employeeId, Integer reviewingEmployeeId, String reviewNote) {
        Employee employee = employees.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此員工申請"));

        if (employee.getDeletedAt() != null) {
            throw new BusinessRuleException("此員工申請已被刪除");
        }
        if (employee.getStatus() != EmployeeStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException("此員工帳號目前不是待審核狀態");
        }

        Employee reviewer = employees.findById(reviewingEmployeeId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到審核者的員工資料"));

        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setReviewedBy(reviewer);
        employee.setReviewedAt(LocalDateTime.now());
        employee.setReviewNote(reviewNote); // 選填
    }

    @Transactional
    public void reject(Integer employeeId, Integer reviewingEmployeeId, String reason) {
        Employee employee = employees.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此員工申請"));

        if (employee.getDeletedAt() != null) {
            throw new BusinessRuleException("此員工申請已被刪除");
        }
        if (employee.getStatus() != EmployeeStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException("此員工帳號目前不是待審核狀態");
        }

        Employee reviewer = employees.findById(reviewingEmployeeId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到審核者的員工資料"));

        employee.setStatus(EmployeeStatus.REJECTED);
        employee.setReviewedBy(reviewer);
        employee.setReviewedAt(LocalDateTime.now());
        employee.setReviewNote(reason); // 必填，由 controller 端的 DTO 驗證擋
    }
}
