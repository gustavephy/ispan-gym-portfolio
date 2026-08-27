package tw.com.ispan.gym.controller.member;

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

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.coach.CoachBookingHistoryResponse;
import tw.com.ispan.gym.dto.coach.GroupClassBookingResponse;
import tw.com.ispan.gym.dto.coach.MemberCourseQuotaResponse;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.dto.member.AdminMemberResponse;
import tw.com.ispan.gym.dto.member.AdminMemberSummaryResponse;
import tw.com.ispan.gym.dto.member.AdminMemberUpdateRequest;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;
import tw.com.ispan.gym.enums.coach.GroupClassBookingStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.service.coach.CoachBookingService;
import tw.com.ispan.gym.service.coach.GroupClassService;
import tw.com.ispan.gym.service.member.AdminMemberService;

/**
 * ADMIN 會員管理：列表／單筆查詢／代改個資／停用／恢復。
 *
 * <p>/api/admin/** 已由正式 SecurityConfig 的 URL matcher 使用 hasRole("ADMIN") 保護，
 * 這裡的方法層級 @PreAuthorize 是第二層防護，比照 ProductController 既有風格——
 * 但也因為 URL 層會先攔截非 ADMIN，EndpointAuthorizationTest 用真牌 SecurityConfig
 * 測這幾個端點時，只能證明 URL 層規則生效，證明不了方法層 @PreAuthorize 本身是否正確；
 * 後者由獨立的 AdminMemberMethodSecurityTest（刻意放寬 URL 層）驗證。
 *
 * <p>page／size／sort／direction 的合法性驗證與排序白名單展開，全部交給
 * AdminMemberService，這裡不重複判斷。UserStatus 轉換失敗（前端傳入不存在的狀態字串）
 * 由既有的 MethodArgumentTypeMismatchException handler 回 400，不需要額外處理。
 */
@RestController
@RequestMapping("/api/admin/members")
public class AdminMemberController {

    private final AdminMemberService adminMemberService;
    private final CoachBookingService coachBookingService;
    private final GroupClassService groupClassService;

    public AdminMemberController(AdminMemberService adminMemberService,
            CoachBookingService coachBookingService, GroupClassService groupClassService) {
        this.adminMemberService = adminMemberService;
        this.coachBookingService = coachBookingService;
        this.groupClassService = groupClassService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Page<AdminMemberSummaryResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return ResponseEntity.ok(adminMemberService.list(keyword, status, page, size, sort, direction));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{memberId}")
    public AdminMemberResponse getById(@PathVariable Integer memberId) {
        return adminMemberService.getById(memberId);
    }

    /**
     * 查詢指定會員的課程額度。與會員端 GET /api/member/quotas 共用
     * CoachBookingService.getMyQuotas，差異只在 memberId 是管理者指定而非從 token 反查。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{memberId}/course-quotas")
    public ResponseEntity<List<MemberCourseQuotaResponse>> getQuotas(
            @PathVariable Integer memberId) {
        return ResponseEntity.ok(coachBookingService.getMyQuotas(memberId));
    }

    /**
     * 查詢指定會員的團課報名紀錄。與會員端 GET /api/member/group-classes/bookings 共用
     * GroupClassService 的篩選／分頁邏輯，差異只在 memberId 是管理者指定而非從 token 反查，
     * 且改用 {@link GroupClassService#getMemberBookingsForAdmin} 依開課時間排序
     * （會員端版本把 BOOKED 排在 CANCELLED 前面，是為了「自己看」設計的，管理者查任意會員的
     * 紀錄是要核對資料，依時間排序更直覺）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{memberId}/group-class-bookings")
    public ResponseEntity<PageResponse<GroupClassBookingResponse>> getGroupClassBookings(
            @PathVariable Integer memberId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) GroupClassBookingStatus status,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return ResponseEntity.ok(groupClassService.getMemberBookingsForAdmin(memberId, page, size, search, status,
                dateFrom, dateTo));
    }

    /**
     * 查詢指定會員的私教課預約歷史。與會員端 GET /api/member/bookings 共用
     * CoachBookingService.getMyBookings，差異只在 memberId 是管理者指定而非從 token 反查。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{memberId}/coach-bookings")
    public ResponseEntity<PageResponse<CoachBookingHistoryResponse>> getCoachBookings(
            @PathVariable Integer memberId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) CoachBookingStatus status,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return ResponseEntity.ok(coachBookingService.getMyBookings(memberId, page, size, status, dateFrom, dateTo));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{memberId}")
    public AdminMemberResponse update(@PathVariable Integer memberId,
            @Valid @RequestBody AdminMemberUpdateRequest request) {
        return adminMemberService.update(memberId, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{memberId}/suspend")
    public void suspend(@PathVariable Integer memberId) {
        adminMemberService.suspend(memberId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{memberId}/activate")
    public void activate(@PathVariable Integer memberId) {
        adminMemberService.activate(memberId);
    }
}
