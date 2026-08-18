package tw.com.ispan.gym.service.member;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.member.AdminMemberResponse;
import tw.com.ispan.gym.dto.member.AdminMemberSummaryResponse;
import tw.com.ispan.gym.dto.member.AdminMemberUpdateRequest;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.InvalidRequestException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * ADMIN 會員管理：列表／單筆查詢／代改個資／停用／恢復。
 * 一律排除軟刪除，這個 MVP 階段沒有任何「查看已軟刪除會員」的參數（封版決定）。
 *
 * <p>update()／suspend()／activate() 共用 lockOrThrow()，皆以悲觀鎖讀取——
 * 三者對同一筆 Member 互相序列化，避免 Hibernate 全欄位回寫（Member 沒有 @DynamicUpdate）
 * 讓其中一個操作用記憶體裡的舊值覆蓋另一個操作剛寫入的欄位（同一類問題見
 * MemberProfileService 自助修改的設計說明）。
 */
@Service
@Transactional(readOnly = true)
public class AdminMemberService {

    private static final Set<String> ALLOWED_SORTS = Set.of("id", "name", "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final MemberRepository members;

    public AdminMemberService(MemberRepository members) {
        this.members = members;
    }

    public Page<AdminMemberSummaryResponse> list(String keyword, UserStatus status, int page, int size,
            String sort, String direction) {
        String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Pageable pageable = buildPageable(page, size, sort, direction);
        return members.findMembersForAdmin(normalizedKeyword, status, pageable)
                .map(AdminMemberSummaryResponse::from);
    }

    public AdminMemberResponse getById(Integer memberId) {
        Member member = members.findById(memberId)
                .filter(m -> m.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此會員"));
        return AdminMemberResponse.from(member);
    }

    @Transactional
    public AdminMemberResponse update(Integer memberId, AdminMemberUpdateRequest request) {
        Member member = lockOrThrow(memberId);

        member.setFirstName(request.getFirstName());
        member.setLastName(request.getLastName());
        member.setPhone(request.getPhone());
        member.setDob(request.getDob());
        member.setGender(request.getGender());
        member.setEmergencyContact(request.getEmergencyContact());
        member.setEmergencyPhone(request.getEmergencyPhone());
        member.setZipCode(request.getZipCode());
        member.setCity(request.getCity());
        member.setDistrict(request.getDistrict());
        member.setAddress(request.getAddress());

        return AdminMemberResponse.from(member);
    }

    @Transactional
    public void suspend(Integer memberId) {
        Member member = lockOrThrow(memberId);
        switch (member.getStatus()) {
            case ACTIVE -> member.setStatus(UserStatus.SUSPENDED);
            case SUSPENDED -> throw new BusinessRuleException("此會員已是停權狀態");
            case PENDING -> throw new BusinessRuleException("此會員尚未完成驗證，無法停用");
        }
    }

    @Transactional
    public void activate(Integer memberId) {
        Member member = lockOrThrow(memberId);
        switch (member.getStatus()) {
            case SUSPENDED -> member.setStatus(UserStatus.ACTIVE);
            case ACTIVE -> throw new BusinessRuleException("此會員已是啟用狀態");
            case PENDING -> throw new BusinessRuleException("此會員尚未完成驗證，請由會員完成 Email 驗證");
        }
    }

    private Member lockOrThrow(Integer memberId) {
        return members.findByIdForUpdate(memberId)
                .filter(m -> m.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此會員"));
    }

    private Pageable buildPageable(int page, int size, String sort, String direction) {
        if (page < 0) {
            throw new InvalidRequestException("頁碼不可小於 0");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("每頁筆數必須介於 1 到 " + MAX_PAGE_SIZE);
        }

        String normalizedSort = sort == null ? "id" : sort.trim();
        String effectiveSort = ALLOWED_SORTS.contains(normalizedSort) ? normalizedSort : "id";

        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        List<Sort.Order> orders = new ArrayList<>();
        switch (effectiveSort) {
            case "name" -> {
                orders.add(new Sort.Order(dir, "lastName"));
                orders.add(new Sort.Order(dir, "firstName"));
                orders.add(new Sort.Order(Sort.Direction.ASC, "id"));
            }
            case "createdAt" -> {
                orders.add(new Sort.Order(dir, "createdAt"));
                orders.add(new Sort.Order(Sort.Direction.ASC, "id"));
            }
            default -> orders.add(new Sort.Order(dir, "id"));
        }
        return PageRequest.of(page, size, Sort.by(orders));
    }
}
