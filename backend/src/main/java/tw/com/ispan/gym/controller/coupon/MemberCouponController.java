package tw.com.ispan.gym.controller.coupon;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coupon.CouponResponse;
import tw.com.ispan.gym.dto.coupon.MemberCouponResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.coupon.MemberCouponService;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
@Slf4j
public class MemberCouponController {

    // 【合併時的修改說明】原本寫的 AuthUtils 在專案裡不存在（分支上也沒有這個類別，
    // 無法編譯），dev 上取得登入者 id 的統一慣例是 CurrentUserProvider
    // （教練排班、AI 健康等 controller 都用它），這裡跟著改用同一套；
    // @AuthenticationPrincipal UserDetails 參數因此不再需要。
    // 另外移除了誤匯入主程式的 MockMvcResultHandlers.log（測試專用），log 走 @Slf4j。
    private final MemberCouponService memberCouponService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 查詢目前可領取的公開優惠券
     * 篩選出有效期限內且當前會員尚未領取過的優惠券，供前台「領取中心」顯示。
     */
    @GetMapping("/available")
    public ResponseEntity<List<CouponResponse>> getAvailableCoupons() {
        Integer memberId = currentUserProvider.requireCurrentUserId();
        log.info("Member {} request to get available coupons for claim", memberId);
        return ResponseEntity.ok(memberCouponService.getAvailableCouponsForClaim(memberId));
    }

    /**
     * 手動領取優惠券
     * 會員在前台點擊「立即領取」時呼叫，將該券綁定至會員名下。
     */
    @PostMapping("/claim/{couponId}")
    public ResponseEntity<MemberCouponResponse> claimCoupon(
            @PathVariable Integer couponId) {
        Integer memberId = currentUserProvider.requireCurrentUserId();
        log.info("Member {} request to claim coupon: {}", memberId, couponId);
        return ResponseEntity.ok(memberCouponService.claimCoupon(memberId, couponId));
    }

    /**
     * 查詢自己已領取的優惠券列表
     * 包含已使用、未使用、已過期的歷史清單。
     */
    @GetMapping("/my")
    public ResponseEntity<List<MemberCouponResponse>> getMyCoupons() {
        Integer memberId = currentUserProvider.requireCurrentUserId();
        log.info("Member {} request to get own coupons list", memberId);
        return ResponseEntity.ok(memberCouponService.getMyCoupons(memberId));
    }

    /**
     * 查詢自己尚未使用的優惠券
     * 結帳頁面呼叫，供會員下拉選取要折抵的優惠券。
     * 自動排除已過期或已使用的券。
     */
    @GetMapping("/my-unused")
    public ResponseEntity<List<MemberCouponResponse>> getMyUnusedCoupons() {
        Integer memberId = currentUserProvider.requireCurrentUserId();
        log.info("Member {} request to get unused coupons for checkout", memberId);
        return ResponseEntity.ok(memberCouponService.getMyUnusedCoupons(memberId));
    }
}