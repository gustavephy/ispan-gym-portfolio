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
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponQueryStatus;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.member.MemberCouponRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 員工端（管理員）優惠券操作：範本 CRUD、統計、群發／指定發放。
 *
 * 【合併時的修改說明】類別的拆分方式（員工端／會員端各一支 service）與方法命名
 * 依 feat/coupon-controller 的設計保留；方法「內容」則採 dev 上 CouponService 的版本，
 * 原因如下，請作者確認：
 * 1. dev 版已把 CustomException 換成統一的 BusinessRuleException／ResourceNotFoundException
 *    家族（CustomException 已在 dev 的例外重構中移除，GlobalExceptionHandler 不再認得它）。
 * 2. 分支版 getAllCoupons 的關鍵字過濾條件是 keyword.isBlank()（漏了驚嘆號），
 *    效果是「有輸入關鍵字時反而不過濾」，dev 版已修正為 !keyword.isBlank()。
 * 3. 分支版 import 了 MockMvcResultHandlers.log（測試專用類別）進主程式，已移除，
 *    log 一律走 @Slf4j。
 * 4. 分支引用的 entity.Member／enums.MemberStatus／repository.MemberRepository
 *    在 dev 上分別是 entity.member.Member／enums.member.UserStatus／
 *    repository.member.MemberRepository（identity 重構後的實際位置），已跟著對齊。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeCouponService {

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
	public int distributeCouponToAllMembers(Integer couponId) {
		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new ResourceNotFoundException("優惠券不存在"));

		if (coupon.getStatus() != CouponStatus.ACTIVE) {
			throw new BusinessRuleException("優惠券已停用，無法發放");
		}

		// 獲取系統中所有帳號處於『啟用（正常）』狀態的會員，以便將優惠券群發給他們。
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
	public int distributeToMembers(Integer couponId, List<Integer> memberIds) {
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
}
