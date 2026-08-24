package tw.com.ispan.gym.controller.coach;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coach.MemberCourseQuotaResponse;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.coach.MemberCourseQuotaService;

/**
 * 會員查詢自己私教課額度的對外 API。memberId 一律從 CurrentUserProvider 取得，
 * 不接受前端自行傳入，避免有人偽造成別的會員身分查詢。
 */
@RestController
@RequestMapping("/api/member")
@RequiredArgsConstructor
public class MemberCourseQuotaController {

    private final CurrentUserProvider currentUserProvider;
    private final MemberCourseQuotaService memberCourseQuotaService;

    @GetMapping("/course-quotas")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<PageResponse<MemberCourseQuotaResponse>> getMyQuotas(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Integer memberId = currentUserProvider.requireCurrentMemberId();
        return ResponseEntity.ok(memberCourseQuotaService.getQuotasByMember(memberId, page, size));
    }
}
