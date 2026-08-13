package tw.com.ispan.gym.service.coupon;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coupon.CouponRequest;
import tw.com.ispan.gym.dto.coupon.CouponResponse;
import tw.com.ispan.gym.dto.coupon.CouponStatsResponse;
import tw.com.ispan.gym.dto.coupon.MemberCouponResponse;
import tw.com.ispan.gym.entity.Member;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.MemberStatus;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponQueryStatus;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.CustomException;
import tw.com.ispan.gym.repository.MemberRepository;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.member.MemberCouponRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberCouponService {
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final MemberRepository memberRepository;

    /** 會員：查詢可以領取的優惠券列表 */
    public List<CouponResponse> getAvailableCouponsForClaim(Integer memberId) {
        LocalDate today = LocalDate.now();
        List<Coupon> allCoupons = couponRepository.findAll();

        return allCoupons.stream()
                .filter(c -> c.getStatus() == CouponStatus.ACTIVE)
				.filter(c -> c
						.getEndDate() == null || !c.getEndDate().isBefore(today))
                .filter(c -> c.getClaimedQuantity() < c.getTotalQuantity())
                .filter(c -> !memberCouponRepository.existsByMemberIdAndCouponId(memberId, c.getCouponId()))
                .map(CouponResponse::from)
                .collect(Collectors.toList());
    }

    /** 會員：領取優惠券 */
    @Transactional
    public MemberCouponResponse claimCoupon(Integer memberId, Integer couponId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(HttpStatus.NOT_FOUND, "會員不存在"));

        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new CustomException(HttpStatus.NOT_FOUND, "優惠券不存在或已下架"));

        if (coupon.getStatus() != CouponStatus.ACTIVE) {
            throw new CustomException(HttpStatus.BAD_REQUEST, "該優惠券已被停用");
        }

        LocalDate today = LocalDate.now();
        if (coupon.getEndDate() != null && coupon.getEndDate().isBefore(today)) {
            throw new CustomException(HttpStatus.BAD_REQUEST, "該優惠券已逾期");
        }

        if (coupon.getClaimedQuantity() >= coupon.getTotalQuantity()) {
            throw new CustomException(HttpStatus.BAD_REQUEST, "優惠券已被領取一空");
        }

        if (memberCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
            throw new CustomException(HttpStatus.BAD_REQUEST, "您已經領取過此優惠券");
        }

        coupon.setClaimedQuantity(coupon.getClaimedQuantity() + 1);
        couponRepository.save(coupon);

        MemberCoupon uc = new MemberCoupon();
        uc.setMember(member);
        uc.setCoupon(coupon);
        uc.setStatus(MemberCouponStatus.UNUSED);
        MemberCoupon saved = memberCouponRepository.save(uc);

        log.info("Member {} successfully claimed coupon: {}", memberId, coupon.getCode());
        return MemberCouponResponse.from(saved);
    }

    /** 會員：取得自己的優惠券清單 */
    public List<MemberCouponResponse> getMyCoupons(Integer memberId) {
        return memberCouponRepository.findByMemberId(memberId).stream()
                .map(MemberCouponResponse::from)
                .collect(Collectors.toList());
    }

    /** 會員：取得自己目前可套用的優惠券清單（未使用 & 未到期） */
    public List<MemberCouponResponse> getMyUnusedCoupons(Integer memberId) {
        LocalDate today = LocalDate.now();
        return memberCouponRepository.findByMemberIdAndStatus(memberId, MemberCouponStatus.UNUSED).stream()
                .filter(uc -> uc.getCoupon().getStatus() == CouponStatus.ACTIVE)
                .filter(uc -> uc.getCoupon().getEndDate() == null || !uc.getCoupon().getEndDate().isBefore(today))
                .map(MemberCouponResponse::from)
                .collect(Collectors.toList());
    }
}
