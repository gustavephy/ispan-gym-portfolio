package tw.com.ispan.gym.dto.employee;

import java.time.LocalDate;
import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;

/**
 * 員工自助查詢用。email 為唯讀欄位（來自 Account），不可透過 EmployeeProfileUpdateRequest 修改。
 * 沒有 updatedAt——比照 MemberProfileResponse，自助視角刻意省略。
 */
public record EmployeeProfileResponse(
        Integer employeeId,
        String email,
        String firstName,
        String lastName,
        String phone,
        EmployeeRole role,
        EmployeeStatus status,
        LocalDate hireDate,
        boolean isTrainer,
        LocalDateTime createdAt) {

    public static EmployeeProfileResponse from(Employee employee, boolean isTrainer) {
        return new EmployeeProfileResponse(
                employee.getId(),
                employee.getAccount().getEmail(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getPhone(),
                employee.getRole(),
                employee.getStatus(),
                employee.getHireDate(),
                isTrainer,
                employee.getCreatedAt());
    }
}
