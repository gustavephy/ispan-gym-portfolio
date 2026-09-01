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
 * 員工審核：核准／拒絕其他人的申請。<strong>ADMIN-only（2026-08-31 政策定案）</strong>——
 * 一般 EMPLOYEE 與只有 TRAINER 身分者不可審核；具 ADMIN 身分者（含同時是教練的 ADMIN）可以。
 * 路徑仍獨立於 /api/admin/**（那條 prefix 還掛著訂單、商品等其他後台功能），SecurityConfig
 * 對應 matcher 已改為 hasRole("ADMIN")；方法層級的 @PreAuthorize 是第二層防護，比照
 * ProductController 既有風格。角色為疊加式，ADMIN 帳號必同時具 ROLE_EMPLOYEE，
 * approve／reject 內用 requireCurrentEmployeeId() 記錄審核者仍可正常運作。
 * 日後若要放寬回「任何在職員工皆可審核」，把這裡與 SecurityConfig 改回 hasRole('EMPLOYEE')
 * 即可，既有申請、審核紀錄與 reviewedBy 不受影響。
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

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/pending")
    public List<PendingEmployeeResponse> listPending() {
        return reviewService.listPending();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{employeeId}/approve")
    public void approve(@PathVariable Integer employeeId, @Valid @RequestBody EmployeeApproveRequest request) {
        reviewService.approve(employeeId, currentUser.requireCurrentEmployeeId(), request.getReviewNote());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{employeeId}/reject")
    public void reject(@PathVariable Integer employeeId, @Valid @RequestBody EmployeeRejectRequest request) {
        reviewService.reject(employeeId, currentUser.requireCurrentEmployeeId(), request.getReason());
    }
}
