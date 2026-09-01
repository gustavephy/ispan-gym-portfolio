package tw.com.ispan.gym.dto.employee;

import java.time.LocalDate;
import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.employee.Employee;

/** 員工審核清單用——這條路徑已受 hasRole(ADMIN) 保護（2026-08-31 起 ADMIN-only），帶真實資料供審核判斷 */
public record PendingEmployeeResponse(
        Integer employeeId,
        String firstName,
        String lastName,
        String email,
        String phone,
        String role,
        LocalDate hireDate,
        LocalDateTime createdAt) {

    public static PendingEmployeeResponse from(Employee employee) {
        return new PendingEmployeeResponse(
                employee.getId(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getAccount().getEmail(),
                employee.getPhone(),
                employee.getRole().name(),
                employee.getHireDate(),
                employee.getCreatedAt());
    }
}
