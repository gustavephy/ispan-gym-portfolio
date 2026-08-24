package tw.com.ispan.gym.dto.employee;

import java.time.LocalDate;
import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;

/** ADMIN 員工列表用，刻意精簡，不含審核備註等欄位；查列表不需要額外查 Trainer。 */
public record AdminEmployeeSummaryResponse(
        Integer employeeId,
        String displayName,
        String email,
        String phone,
        EmployeeRole role,
        EmployeeStatus status,
        LocalDate hireDate,
        LocalDateTime createdAt) {

    public static AdminEmployeeSummaryResponse from(Employee employee) {
        String displayName = employee.getLastName() + employee.getFirstName();
        return new AdminEmployeeSummaryResponse(
                employee.getEmployeeId(),
                displayName,
                employee.getAccount().getEmail(),
                employee.getPhone(),
                employee.getRole(),
                employee.getStatus(),
                employee.getHireDate(),
                employee.getCreatedAt());
    }
}
