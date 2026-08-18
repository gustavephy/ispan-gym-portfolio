package tw.com.ispan.gym.controller.member;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.member.MemberProfileResponse;
import tw.com.ispan.gym.dto.member.MemberProfileUpdateRequest;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.security.JwtAuthFilter;
import tw.com.ispan.gym.service.member.MemberProfileService;

/**
 * 會員自助查詢／修改個人資料。
 *
 * <p>accountId 一律由這裡從登入資訊解出、傳進 Service，不接受前端指定——避免有人
 * 偽造成別的會員身分讀取或修改資料（比照 CoachBookingController 既有寫法）。
 *
 * <p>GET 沒有方法層級 @PreAuthorize，只要求已登入（SecurityConfig 的
 * anyRequest().authenticated()）——但「已登入」在這個專案裡實際等於「帳號目前至少
 * 持有一個啟用中的身分」：{@link JwtAuthFilter} 對權限清單為空的帳號直接視為匿名，
 * 完全不寫入 SecurityContext（見 2026-08-15 的既有設計），所以純 SUSPENDED／PENDING
 * 會員（沒有其他啟用中身分）連這個 GET 都打不進來，會在 filter 層就拿到 401，
 * 不是靠這裡的授權規則放行後才由 Service 擋掉。只有當帳號還有其他啟用中身分
 * （例如同時是 ACTIVE 員工）時，才會先通過 filter 層，實際是否存在未軟刪除的
 * Member 資料再交給 Service 判斷。PUT 加 @PreAuthorize("hasRole('MEMBER')")，
 * 非 ACTIVE 的會員拿不到這個角色，會在方法層級被擋下（前提是帳號本身已通過
 * filter 層認證）；Service 內部仍會在鎖後再檢查一次 ACTIVE，兩層互不取代。
 */
@RestController
@RequestMapping("/api/member")
public class MemberProfileController {

    private final MemberProfileService profileService;
    private final CurrentUserProvider currentUser;

    public MemberProfileController(MemberProfileService profileService, CurrentUserProvider currentUser) {
        this.profileService = profileService;
        this.currentUser = currentUser;
    }

    @GetMapping("/profile")
    public MemberProfileResponse getMyProfile() {
        Integer accountId = currentUser.requireCurrentUserId();
        return profileService.getMyProfile(accountId);
    }

    @PreAuthorize("hasRole('MEMBER')")
    @PutMapping("/profile")
    public MemberProfileResponse updateMyProfile(@Valid @RequestBody MemberProfileUpdateRequest request) {
        Integer accountId = currentUser.requireCurrentUserId();
        return profileService.updateMyProfile(accountId, request);
    }
}
