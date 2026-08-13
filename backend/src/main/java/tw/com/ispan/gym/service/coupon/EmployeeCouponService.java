package tw.com.ispan.gym.service.coupon;

import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.log;

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
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponQueryStatus;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.CustomException;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.member.MemberCouponRepository;


@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeCouponService {

	private final CouponRepository couponRepository;
	private final MemberCouponRepository memberCouponRepository;
	private final MemberRepository memberRepository;

	// 建立優惠券(管理員)
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

	// 查詢所有優惠券(管理者用，帶篩選搜尋)
	public List<CouponResponse> getAllCoupons(String keyword, String status, LocalDate startDate, LocalDate EndDate) {
		List<Coupon> coupons = couponRepository.findAll();
		LocalDate today = LocalDate.now();

		return coupons.stream().filter(c -> {
			if (keyword != null && keyword.isBlank()) {
				String kw = keyword.toLowerCase().trim();
				return c.getCode().toLowerCase().contains(kw) || c.getName().toLowerCase().contains(kw);
			}
			return true;
		}).filter(c -> {
			if (startDate != null) {
				return !c.getStartDate().isBefore(startDate);
			}
			return true;
		}).filter(c -> {
			if (status != null && !status.isBlank()) {
				try {
					CouponQueryStatus queryStatus = CouponQueryStatus.valueOf(status.toUpperCase().trim());
					return queryStatus.matches(c, today);
				} catch (IllegalArgumentException e) {
					return true;
				}
			}
			return true;
		}).map(CouponResponse::from).collect(Collectors.toList());
	}

	/** 取得優惠券統計數據 */
	public CouponStatsResponse getCouponStats() {
		List<Coupon> coupons = couponRepository.findAll();
		LocalDate today = LocalDate.now();

		int activeCount = (int) coupons.stream().filter(c -> (c.getStatus() == CouponStatus.ACTIVE)
				&& (c.getStartDate() == null || !c.getStartDate().isAfter(today)) && // null = 即日起生效
				(c.getEndDate() == null || !c.getEndDate().isBefore(today))).count();

		int upcomingCount = (int) coupons.stream().filter(c -> (c.getStatus() == CouponStatus.ACTIVE)
				&& c.getStartDate() != null && c.getStartDate().isAfter(today)).count();

		int expiredCount = (int) coupons.stream().filter(c -> c.getStatus() == CouponStatus.EXPIRED
				|| c.getStatus() == CouponStatus.INACTIVE || (c.getEndDate() != null && c.getEndDate().isBefore(today)))
				.count();

		int totalClaimedCount = coupons.stream().mapToInt(Coupon::getClaimedQuantity).sum();

		return new CouponStatsResponse(activeCount, upcomingCount, expiredCount, totalClaimedCount);
	}

	/** 管理員：更新優惠券範本 */
	@Transactional
	public CouponResponse updateCoupon(Integer id, CouponRequest request) {
		Coupon coupon = couponRepository.findById(id)
				.orElseThrow(() -> new CustomException(HttpStatus.NOT_FOUND, "優惠券不存在"));

		couponRepository.findByCode(request.getCode().trim().toUpperCase()).ifPresent(existing -> {
			if (!existing.getCouponId().equals(id)) {
				throw new CustomException(HttpStatus.BAD_REQUEST, "優惠券代碼已存在");
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

	/** 管理員：將優惠券狀態設為已下架 (0) */
	@Transactional
	public void deleteCoupon(Integer id) {
		Coupon coupon = couponRepository.findById(id)
				.orElseThrow(() -> new CustomException(HttpStatus.NOT_FOUND, "優惠券不存在"));
		coupon.setStatus(CouponStatus.INACTIVE);
		couponRepository.save(coupon);
		log.info("Deactivated coupon ID: {}", id);
	}

	/**
	 * 管理員：群發給所有啟用中的 MEMBER 會員
	 */
	@Transactional
	public int distributeCouponToAllMembers(Integer couponId) {
		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new CustomException(HttpStatus.NOT_FOUND, "優惠券不存在"));

		if (coupon.getStatus() != CouponStatus.ACTIVE) {
			throw new CustomException(HttpStatus.BAD_REQUEST, "優惠券已停用，無法發放");
		}

		// 獲取系統中所有帳號處於『啟用（正常）』狀態的會員，以便將優惠券群發給他們。
		List<Member> members = memberRepository.findAll().stream().filter(u -> u.getStatus() == MemberStatus.ACTIVE)
				.collect(Collectors.toList());

		int count = 0;
		for (Member user : members) {
			if (memberCouponRepository.existsByMemberIdAndCouponId(user.getMemberId(), couponId)) {
				continue;
			}
			coupon.setClaimedQuantity(coupon.getClaimedQuantity() + 1);
			if (coupon.getClaimedQuantity() > coupon.getTotalQuantity()) {
				coupon.setTotalQuantity(coupon.getClaimedQuantity());
			}

			MemberCoupon uc = new MemberCoupon();
			uc.setMember(user);
			uc.setCoupon(coupon);
			uc.setStatus(MemberCouponStatus.UNUSED);
			memberCouponRepository.save(uc);
			count++;
		}
		couponRepository.save(coupon);
		log.info("群發優惠券 {} 成功，共發放給 {} 位會員", coupon.getCode(), count);
		return count;
	}

	/**
	 * 管理員：批量發放給指定 memberId 清單
	 */
	@Transactional
	public int distributeToUsers(Integer couponId, List<Integer> memberIds) {
		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new CustomException(HttpStatus.NOT_FOUND, "優惠券不存在"));

		if (coupon.getStatus() != CouponStatus.ACTIVE) {
			throw new CustomException(HttpStatus.BAD_REQUEST, "優惠券已停用，無法發放");
		}

		int count = 0;
		for (Integer uid : memberIds) {
			Member user = memberRepository.findById(uid).orElse(null);
			if (user == null)
				continue;
			if (memberCouponRepository.existsByMemberIdAndCouponId(uid, couponId))
				continue;

			coupon.setClaimedQuantity(coupon.getClaimedQuantity() + 1);
			if (coupon.getClaimedQuantity() > coupon.getTotalQuantity()) {
				coupon.setTotalQuantity(coupon.getClaimedQuantity());
			}

			MemberCoupon uc = new MemberCoupon();
			uc.setMember(user);
			uc.setCoupon(coupon);
			uc.setStatus(MemberCouponStatus.UNUSED);
			memberCouponRepository.save(uc);
			count++;
		}
		couponRepository.save(coupon);
		log.info("指定發放優惠券 {} 完成，共發放 {} 人", coupon.getCode(), count);
		return count;
	}
}
