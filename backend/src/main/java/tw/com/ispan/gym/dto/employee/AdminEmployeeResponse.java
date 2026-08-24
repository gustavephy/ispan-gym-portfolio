package tw.com.ispan.gym.dto.employee;

import java.time.LocalDate;
import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;

/** ADMIN 單筆查詢用。不回傳 accountId——email 已足以識別帳號，比照 AdminMemberResponse。 */
public record AdminEmployeeResponse(
        Integer employeeId,
        String displayName,
        String email,
        String firstName,
        String lastName,
        String phone,
        EmployeeRole role,
        EmployeeStatus status,
        LocalDate hireDate,
        boolean isTrainer,
        EmployeeReviewerSummary reviewedBy,
        LocalDateTime reviewedAt,
        String reviewNote,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AdminEmployeeResponse from(Employee employee, boolean isTrainer) {
        String displayName = employee.getLastName() + employee.getFirstName();
        Employee reviewer = employee.getReviewedBy();
        EmployeeReviewerSummary reviewedBy = reviewer == null
                ? null
                : new EmployeeReviewerSummary(reviewer.getId(), reviewer.getLastName() + reviewer.getFirstName());
        return new AdminEmployeeResponse(
                employee.getId(),
                displayName,
                employee.getAccount().getEmail(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getPhone(),
                employee.getRole(),
                employee.getStatus(),
                employee.getHireDate(),
                isTrainer,
                reviewedBy,
                employee.getReviewedAt(),
                employee.getReviewNote(),
                employee.getCreatedAt(),
                employee.getUpdatedAt());
    }

    /** 核准／拒絕此員工申請的審核者摘要；始祖帳號無人審核，為 null */
    public record EmployeeReviewerSummary(Integer employeeId, String displayName) {}
}
