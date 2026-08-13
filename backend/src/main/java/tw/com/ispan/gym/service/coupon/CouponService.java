package tw.com.ispan.gym.service.coupon;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coupon.CouponRequest;
import tw.com.ispan.gym.dto.coupon.CouponResponse;
import tw.com.ispan.gym.dto.coupon.CouponStatsResponse;
import tw.com.ispan.gym.dto.coupon.MemberCouponResponse;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.coupon.MemberCoupon;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponQueryStatus;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.coupon.MemberCouponStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.coupon.MemberCouponRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponService {
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final MemberRepository memberRepository;

    /** 管理員：建立優惠券範本 */
    @Transactional
    public CouponResponse createCoupon(CouponRequest request) {
        if (couponRepository.findByCode(request.getCode()).isPresent()) {
            throw new BusinessRuleException("優惠券代碼已存在");
        }

        Coupon coupon = new Coupon();
        coupon.setCode(request.getCode().trim().toUpperCase());
        coupon.setName(request.getName());
        coupon.setType(request.getType());
        coupon.setValue(request.getValue());
        coupon.setMinSpend(request.getMinSpend());
        coupon.setTotalQuantity(request.getTotalQuantity());
        coupon.setStartDate(request.getStartDate());
        coupon.setEndDate(request.getEndDate());
        coupon.setApplicableType(
                request.getApplicableType() != null ? request.getApplicableType() : CouponApplicableType.ALL);

        Coupon saved = couponRepository.save(coupon);
        log.info("Created coupon template: {} (applicableType={})", saved.getCode(), saved.getApplicableType());
        return CouponResponse.from(saved);
    }

    /** 管理員：查詢所有優惠券（帶關鍵字／狀態／起始日篩選） */
    public List<CouponResponse> getAllCoupons(String keyword, String status, LocalDate startDate, LocalDate endDate) {
        List<Coupon> coupons = couponRepository.findAll();
        LocalDate today = LocalDate.now();

        return coupons.stream()
                .filter(c -> {
                    if (keyword != null && !keyword.isBlank()) {
                        String kw = keyword.toLowerCase().trim();
                        return c.getCode().toLowerCase().contains(kw) || c.getName().toLowerCase().contains(kw);
                    }
                    return true;
                })
                .filter(c -> {
                    if (startDate != null) {
                        return !c.getStartDate().isBefore(startDate);
                    }
                    return true;
                })
                .filter(c -> {
                    if (status != null && !status.isBlank()) {
                        try {
                            CouponQueryStatus queryStatus = CouponQueryStatus.valueOf(status.toUpperCase().trim());
                            return queryStatus.matches(c, today);
                        } catch (IllegalArgumentException e) {
                            return true;
                        }
                    }
                    return true;
                })
                .map(CouponResponse::from)
                .collect(Collectors.toList());
    }

    /** 取得優惠券統計數據 */
    public CouponStatsResponse getCouponStats() {
        List<Coupon> coupons = couponRepository.findAll();
        LocalDate today = LocalDate.now();

        int activeCount = (int) coupons.stream()
                .filter(c -> (c.getStatus() == CouponStatus.ACTIVE) &&
                        (c.getStartDate() == null || !c.getStartDate().isAfter(today)) && // null = 即日起生效
                        (c.getEndDate() == null || !c.getEndDate().isBefore(today)))
                .count();

        int upcomingCount = (int) coupons.stream()
                .filter(c -> (c.getStatus() == CouponStatus.ACTIVE) &&
                        c.getStartDate() != null && c.getStartDate().isAfter(today))
                .count();

        int expiredCount = (int) coupons.stream()
                .filter(c -> c.getStatus() == CouponStatus.EXPIRED ||
                        c.getStatus() == CouponStatus.INACTIVE ||
                        (c.getEndDate() != null && c.getEndDate().isBefore(today)))
                .count();

        int totalClaimedCount = coupons.stream()
                .mapToInt(Coupon::getClaimedQuantity)
                .sum();

        return new CouponStatsResponse(activeCount, upcomingCount, expiredCount, totalClaimedCount);
    }

    /** 管理員：更新優惠券範本 */
    @Transactional
    public CouponResponse updateCoupon(Integer id, CouponRequest request) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("優惠券不存在"));

        couponRepository.findByCode(request.getCode().trim().toUpperCase())
                .ifPresent(existing -> {
                    if (!existing.getCouponId().equals(id)) {
                        throw new BusinessRuleException("優惠券代碼已存在");
                    }
                });

        coupon.setCode(request.getCode().trim().toUpperCase());
        coupon.setName(request.getName());
        coupon.setType(request.getType());
        coupon.setValue(request.getValue());
        coupon.setMinSpend(request.getMinSpend());
        coupon.setTotalQuantity(request.getTotalQuantity());
        coupon.setStartDate(request.getStartDate());
        coupon.setEndDate(request.getEndDate());
        if (request.getApplicableType() != null) {
            coupon.setApplicableType(request.getApplicableType());
        }

        Coupon saved = couponRepository.save(coupon);
        log.info("Updated coupon template: {}", saved.getCode());
        return CouponResponse.from(saved);
    }

    /** 管理員：將優惠券狀態設為已下架 */
    @Transactional
    public void deleteCoupon(Integer id) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("優惠券不存在"));
        coupon.setStatus(CouponStatus.INACTIVE);
        couponRepository.save(coupon);
        log.info("Deactivated coupon ID: {}", id);
    }

    /** 管理員：群發給所有啟用中的會員 */
    @Transactional
    public int distributeToAll(Integer couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("優惠券不存在"));

        if (coupon.getStatus() != CouponStatus.ACTIVE) {
            throw new BusinessRuleException("優惠券已停用，無法發放");
        }

        List<Member> members = memberRepository.findAll().stream()
                .filter(m -> m.getStatus() == UserStatus.ACTIVE)
                .collect(Collectors.toList());

        int count = 0;
        for (Member member : members) {
            if (memberCouponRepository.existsByMemberIdAndCouponId(member.getId(), couponId)) {
                continue;
            }
            coupon.setClaimedQuantity(coupon.getClaimedQuantity() + 1);
            if (coupon.getClaimedQuantity() > coupon.getTotalQuantity()) {
                coupon.setTotalQuantity(coupon.getClaimedQuantity());
            }

            MemberCoupon mc = new MemberCoupon();
            mc.setMember(member);
            mc.setCoupon(coupon);
            mc.setStatus(MemberCouponStatus.UNUSED);
            memberCouponRepository.save(mc);
            count++;
        }
        couponRepository.save(coupon);
        log.info("群發優惠券 {} 成功，共發放給 {} 位會員", coupon.getCode(), count);
        return count;
    }

    /** 管理員：批量發放給指定 memberId 清單 */
    @Transactional
    public int distributeToUsers(Integer couponId, List<Integer> memberIds) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("優惠券不存在"));

        if (coupon.getStatus() != CouponStatus.ACTIVE) {
            throw new BusinessRuleException("優惠券已停用，無法發放");
        }

        int count = 0;
        for (Integer memberId : memberIds) {
            Member member = memberRepository.findById(memberId).orElse(null);
            if (member == null)
                continue;
            if (memberCouponRepository.existsByMemberIdAndCouponId(memberId, couponId))
                continue;

            coupon.setClaimedQuantity(coupon.getClaimedQuantity() + 1);
            if (coupon.getClaimedQuantity() > coupon.getTotalQuantity()) {
                coupon.setTotalQuantity(coupon.getClaimedQuantity());
            }

            MemberCoupon mc = new MemberCoupon();
            mc.setMember(member);
            mc.setCoupon(coupon);
            mc.setStatus(MemberCouponStatus.UNUSED);
            memberCouponRepository.save(mc);
            count++;
        }
        couponRepository.save(coupon);
        log.info("指定發放優惠券 {} 完成，共發放 {} 人", coupon.getCode(), count);
        return count;
    }

    /** 會員：查詢可以領取的優惠券列表 */
    public List<CouponResponse> getAvailableCouponsForClaim(Integer memberId) {
        LocalDate today = LocalDate.now();
        List<Coupon> allCoupons = couponRepository.findAll();

        return allCoupons.stream()
                .filter(c -> c.getStatus() == CouponStatus.ACTIVE)
                .filter(c -> c.getEndDate() == null || !c.getEndDate().isBefore(today))
                .filter(c -> c.getClaimedQuantity() < c.getTotalQuantity())
                .filter(c -> !memberCouponRepository.existsByMemberIdAndCouponId(memberId, c.getCouponId()))
                .map(CouponResponse::from)
                .collect(Collectors.toList());
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
    public List<MemberCouponResponse> getMyCoupons(Integer memberId) {
        return memberCouponRepository.findByMemberId(memberId).stream()
                .map(MemberCouponResponse::from)
                .collect(Collectors.toList());
    }

    /** 會員：取得自己目前可套用的優惠券清單（未使用 & 未到期） */
    public List<MemberCouponResponse> getMyUnusedCoupons(Integer memberId) {
        LocalDate today = LocalDate.now();
        return memberCouponRepository.findByMemberIdAndStatus(memberId, MemberCouponStatus.UNUSED).stream()
                .filter(mc -> mc.getCoupon().getStatus() == CouponStatus.ACTIVE)
                .filter(mc -> mc.getCoupon().getEndDate() == null || !mc.getCoupon().getEndDate().isBefore(today))
                .map(MemberCouponResponse::from)
                .collect(Collectors.toList());
    }
}
