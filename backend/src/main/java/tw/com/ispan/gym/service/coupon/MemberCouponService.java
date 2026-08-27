package tw.com.ispan.gym.service.coupon;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coupon.CouponResponse;
import tw.com.ispan.gym.dto.coupon.MemberCouponResponse;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.member.MemberCouponRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 會員端優惠券操作：查可領清單、領取、查自己的券。
 *
 * 【合併時的修改說明】拆分方式與方法命名依 feat/coupon-controller 的設計保留；
 * 方法內容採 dev 上 CouponService 的版本——例外已從 CustomException 換成
 * dev 統一的 BusinessRuleException／ResourceNotFoundException（理由見
 * {@link EmployeeCouponService} 的類別註解，兩支一致）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemberCouponService {

    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final MemberRepository memberRepository;

    /** 會員：查詢可以領取的優惠券列表 */
    public org.springframework.data.domain.Page<CouponResponse> getAvailableCouponsForClaim(Integer memberId, org.springframework.data.domain.Pageable pageable) {
        LocalDate today = LocalDate.now();
        // 為防資料庫數量不多，且記憶體過濾更準確，我們先查出所有 ACTIVE 的優惠券
        List<Coupon> allActive = couponRepository.findByStatus(CouponStatus.ACTIVE, org.springframework.data.domain.Pageable.unpaged()).getContent();
        
        List<CouponResponse> filtered = allActive.stream()
                .filter(c -> c.getEndDate() == null || !c.getEndDate().isBefore(today))
                .filter(c -> c.getClaimedQuantity() < c.getTotalQuantity())
                .filter(c -> !memberCouponRepository.existsByMemberIdAndCouponId(memberId, c.getCouponId()))
                .map(CouponResponse::from)
                .collect(Collectors.toList());

        // 手動分頁
        if (pageable.isUnpaged()) {
            return new org.springframework.data.domain.PageImpl<>(filtered, pageable, filtered.size());
        }
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filtered.size());
        
        List<CouponResponse> subList = (start >= filtered.size()) ? List.of() : filtered.subList(start, end);
        return new org.springframework.data.domain.PageImpl<>(subList, pageable, filtered.size());
    }

    /** 會員：領取優惠券 */
    @Transactional
    public MemberCouponResponse claimCoupon(Integer memberId, Integer couponId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("會員不存在"));

        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("優惠券不存在或已下架"));

        if (coupon.getStatus() != CouponStatus.ACTIVE) {
            throw new BusinessRuleException("該優惠券已被停用");
        }

        LocalDate today = LocalDate.now();
        if (coupon.getEndDate() != null && coupon.getEndDate().isBefore(today)) {
            throw new BusinessRuleException("該優惠券已逾期");
        }

        if (coupon.getClaimedQuantity() >= coupon.getTotalQuantity()) {
            throw new BusinessRuleException("優惠券已被領取一空");
        }

        if (memberCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
            throw new BusinessRuleException("您已經領取過此優惠券");
        }

        coupon.setClaimedQuantity(coupon.getClaimedQuantity() + 1);
        couponRepository.save(coupon);

        MemberCoupon mc = new MemberCoupon();
        mc.setMember(member);
        mc.setCoupon(coupon);
        mc.setStatus(MemberCouponStatus.UNUSED);
        MemberCoupon saved = memberCouponRepository.save(mc);

        log.info("Member {} successfully claimed coupon: {}", memberId, coupon.getCode());
        return MemberCouponResponse.from(saved);
    }

    /** 會員：取得自己的優惠券清單 */
    public org.springframework.data.domain.Page<MemberCouponResponse> getMyCoupons(Integer memberId, MemberCouponStatus status, org.springframework.data.domain.Pageable pageable) {
        if (status != null) {
            return memberCouponRepository.findByMemberIdAndStatus(memberId, status, pageable)
                    .map(MemberCouponResponse::from);
        }
        return memberCouponRepository.findByMemberId(memberId, pageable)
                .map(MemberCouponResponse::from);
    }

    /** 會員：取得自己目前可套用的優惠券清單（未使用 & 未到期） */
    public List<MemberCouponResponse> getMyUnusedCoupons(Integer memberId) {
        LocalDate today = LocalDate.now();
        // 結帳下拉選單不需要分頁，故這裡維持全量拉取
        return memberCouponRepository.findByMemberIdAndStatus(memberId, MemberCouponStatus.UNUSED, org.springframework.data.domain.Pageable.unpaged()).getContent().stream()
                .filter(mc -> mc.getCoupon().getStatus() == CouponStatus.ACTIVE)
                .filter(mc -> mc.getCoupon().getEndDate() == null || !mc.getCoupon().getEndDate().isBefore(today))
                .map(MemberCouponResponse::from)
                .collect(Collectors.toList());
    }
}
