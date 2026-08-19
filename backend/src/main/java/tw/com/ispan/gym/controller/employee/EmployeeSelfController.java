package tw.com.ispan.gym.controller.employee;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tw.com.ispan.gym.dto.employee.EmployeeSelfResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;

/**
 * 員工自身基本資料查詢。目前只有 employeeId 一個用途：聊天室教練端組裝
 * room_id（member_{memberId}_employee_{employeeId}）需要先知道自己的 employee_id，
 * 對應 MemberSelfController 給會員端用的同一種需求。
 */
@RestController
public class EmployeeSelfController {
    private final CurrentUserProvider currentUser;

    public EmployeeSelfController(CurrentUserProvider currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/api/employees/me")
    public EmployeeSelfResponse getSelf() {
        return new EmployeeSelfResponse(currentUser.requireCurrentEmployeeId());
    }
}
