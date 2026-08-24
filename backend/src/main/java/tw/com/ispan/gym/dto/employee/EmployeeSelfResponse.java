package tw.com.ispan.gym.dto.employee;

/** 登入員工查詢自己的 employee_id，供前端組裝聊天室 room_id（member_{id}_employee_{employeeId}）使用。 */
public record EmployeeSelfResponse(Integer employeeId) {
}
