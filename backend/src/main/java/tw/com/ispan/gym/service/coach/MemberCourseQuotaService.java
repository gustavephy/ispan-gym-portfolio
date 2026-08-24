package tw.com.ispan.gym.service.coach;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coach.MemberCourseQuotaResponse;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.entity.coach.MemberCourseQuota;
import tw.com.ispan.gym.repository.coach.MemberCourseQuotaRepository;

/**
 * 會員課程包額度查詢服務。純唯讀，不涉及 book/consume/refund 這些交易邏輯
 * （那些留在 CoachBookingService，因為額度異動是「訂課/核銷/取消」這個業務流程
 * 的一部分，不是這個服務的職責）。
 *
 * <p>getQuotasByMember 同時給會員端跟管理端使用：兩邊查詢邏輯完全相同，
 * 差異只在呼叫端傳入的 memberId 是否可信，這一層驗證交給各自的 Controller
 * 處理（會員端強制用自己的 id，管理端允許查任意 id 但整支 Controller 已經
 * 被 @PreAuthorize("hasRole('ADMIN')") 保護），Service 層不重複判斷身分。
 */
@Service
@RequiredArgsConstructor
public class MemberCourseQuotaService {

    private final MemberCourseQuotaRepository memberCourseQuotaRepository;

    @Transactional(readOnly = true)
    public PageResponse<MemberCourseQuotaResponse> getQuotasByMember(Integer memberId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<MemberCourseQuota> result = memberCourseQuotaRepository.findByMemberId(memberId, pageable);

        java.util.List<MemberCourseQuotaResponse> items = result.getContent().stream()
                .map(quota -> new MemberCourseQuotaResponse(
                        quota.getId(),
                        quota.getTrainer().getId(),
                        quota.getTrainer().getEmployee().getLastName()
                                + quota.getTrainer().getEmployee().getFirstName(),
                        quota.getCourseName(),
                        quota.getRemainingCourse(),
                        quota.getReservedCourse(),
                        quota.getUsedCourse(),
                        quota.getTotal(),
                        quota.getStatus(),
                        quota.getExpiryDate()))
                .toList();

        return new PageResponse<>(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }
}
