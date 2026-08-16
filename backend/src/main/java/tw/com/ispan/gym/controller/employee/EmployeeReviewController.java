package tw.com.ispan.gym.controller.employee;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.employee.EmployeeApproveRequest;
import tw.com.ispan.gym.dto.employee.EmployeeRejectRequest;
import tw.com.ispan.gym.dto.employee.PendingEmployeeResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.employee.EmployeeReviewService;

/**
 * 員工審核：核准／拒絕其他人的申請。任何 ACTIVE 員工皆可操作，不限管理者——
 * 路徑獨立於 /api/admin/**（那條 prefix 還掛著訂單、商品等真正的管理員後台功能，
 * 不能共用同一個 hasRole 規則），這裡的 SecurityConfig matcher 是 hasRole(EMPLOYEE)。
 * 方法層級再加一次 @PreAuthorize 是雙重把關，比照 ProductController 既有風格。
 */
@RestController
@RequestMapping("/api/employees/reviews")
public class EmployeeReviewController {

    private final EmployeeReviewService reviewService;
    private final CurrentUserProvider currentUser;

    public EmployeeReviewController(EmployeeReviewService reviewService, CurrentUserProvider currentUser) {
        this.reviewService = reviewService;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasRole('EMPLOYEE')")
    @GetMapping("/pending")
    public List<PendingEmployeeResponse> listPending() {
        return reviewService.listPending();
    }

    @PreAuthorize("hasRole('EMPLOYEE')")
    @PostMapping("/{employeeId}/approve")
    public void approve(@PathVariable Integer employeeId, @Valid @RequestBody EmployeeApproveRequest request) {
        reviewService.approve(employeeId, currentUser.requireCurrentEmployeeId(), request.getReviewNote());
    }

    @PreAuthorize("hasRole('EMPLOYEE')")
    @PostMapping("/{employeeId}/reject")
    public void reject(@PathVariable Integer employeeId, @Valid @RequestBody EmployeeRejectRequest request) {
        reviewService.reject(employeeId, currentUser.requireCurrentEmployeeId(), request.getReason());
    }
}
