package tw.com.ispan.gym.service;

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
import tw.com.ispan.gym.entity.Coupon;
import tw.com.ispan.gym.enums.CouponApplicableType;
import tw.com.ispan.gym.enums.CouponQueryStatus;
import tw.com.ispan.gym.exception.CustomException;
import tw.com.ispan.gym.repository.CouponRepository;
import tw.com.ispan.gym.repository.UserCouponRepository;
import tw.com.ispan.gym.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponService {
	private final CouponRepository couponRepository;
	private final UserCouponRepository userCouponRepository;
	private final UserRepository userRepository;
	
	//建立優惠券(管理員)
	@Transactional
	private CouponResponse createCoupon(CouponRequest request) {
		if (couponRepository.findByCode(request.getCode()).isPresent()) {
			throw new CustomException(HttpStatus.BAD_REQUEST,"優惠券代碼已存在");
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
		coupon.setApplicableType(request.getApplicableType() != null ? request.getApplicableType() : CouponApplicableType.ALL);
		
		Coupon saved = couponRepository.save(coupon);
		log.info("Created coupon template: {} (applicableType={})",saved.getCode(),saved.getApplicableType());
		return CouponResponse.from(saved);
	}
	
	//查詢所有優惠券(管理者用，帶篩選搜尋)
	public List<CouponResponse> getAllCoupons(String keyword,String status,LocalDate startDate,LocalDate EndDate){
		List<Coupon> coupons = couponRepository.findAll();
		LocalDate today = LocalDate.now();
		
		return coupons.stream()
				.filter(c->{
					if(keyword != null && keyword.isBlank()) {
						String kw = keyword.toLowerCase().trim();
						return c.getCode().toLowerCase().contains(kw) || c.getName().toLowerCase().contains(kw);
						}
					return true;
				})
				.filter(c->{
					if (startDate != null) {
						return !c.getStartDate().isBefore(startDate);
					}
					return true;
				})
				.filter(c->{
					if(status != null && !status.isBlank()) {
						try {
							CouponQueryStatus queryStatus = CouponQueryStatus.valueOf(status.toUpperCase().trim());
							return queryStatus.matches(c, today);
						}catch (IllegalArgumentException e) {
							return true;
						}
					}
					return true;
				})
				.map(CouponResponse::from)
				.collect(Collectors.toList());
	}
	
//	public CouponStatsResponse getCouponStats() {
//		return;
//	}
}
