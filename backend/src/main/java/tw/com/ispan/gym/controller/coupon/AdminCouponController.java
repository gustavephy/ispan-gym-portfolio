package tw.com.ispan.gym.controller.coupon;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coupon.CouponRequest;
import tw.com.ispan.gym.dto.coupon.CouponResponse;
import tw.com.ispan.gym.dto.coupon.CouponStatsResponse;
import tw.com.ispan.gym.service.coupon.EmployeeCouponService;

@RestController
@RequestMapping("/api/admin/coupons")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminCouponController {

	private final EmployeeCouponService couponService;

	// 查詢所有優惠券(管理員)
	@GetMapping
	public ResponseEntity<Page<CouponResponse>> getAllCoupons(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) LocalDate startDate,
			@RequestParam(required = false) LocalDate endDate,
			@PageableDefault(size = 10, page = 0) Pageable pageable) {
		log.info(
				"Request to get all coupons: keyword={}, status={}, startDate={}, endDate={}, pageable={}",
				keyword, status, startDate, endDate, pageable);
		return ResponseEntity.ok(couponService.getAllCoupons(keyword, status, startDate, endDate, pageable));
	}

	// 取得優惠券統計數據(管理員)
	@GetMapping("/stats")
	public ResponseEntity<CouponStatsResponse> getCouponStats() {
		log.info("Request to get coupon stats");
		return ResponseEntity.ok(couponService.getCouponStats());
	}

	// 新增優惠券(管理員)
	@PostMapping
	public ResponseEntity<CouponResponse> createCoupon(@Valid @RequestBody CouponRequest couponRequest) {
		log.info("Employee request to create coupon: {}", couponRequest.getCode());
		return ResponseEntity.ok(couponService.createCoupon(couponRequest));
	}

	// 更新優惠券(管理員)
	@PutMapping("/{couponId}")
	public ResponseEntity<CouponResponse> updateCoupon(
			@PathVariable Integer couponId,
			@Valid @RequestBody CouponRequest couponRequest) {
		log.info("Employee request to update coupon: {}", couponId);
		return ResponseEntity.ok(couponService.updateCoupon(couponId, couponRequest));
	}

	// 下架優惠券(管理員)
	@DeleteMapping("/{couponId}")
	public ResponseEntity<String> deleteCoupon(
			@PathVariable Integer couponId) {
		log.info("Employee request to delete/deactivate coupon: {}", couponId);
		couponService.deleteCoupon(couponId);
		return ResponseEntity.ok("優惠券已下架");
	}

	// 群發優惠券(管理員)
	@PostMapping("/{couponId}/distribute-all")
	public ResponseEntity<Map<String, Object>> distributeToAll(
			@PathVariable Integer couponId) {
		log.info("Employee request to distribute coupon {} to all active members: {}", couponId);
		int count = couponService.distributeCouponToAllMembers(couponId);
		return ResponseEntity
				.ok(Map.of("success", true, "distributedCount", count, "messsage", "成功發放給" + count + "位會員"));

	}

	// 發給指定會員
	@PostMapping("/{couponId}/distribute")
	public ResponseEntity<Map<String, Object>> distributeToMembers(
			@PathVariable Integer couponId,
			@RequestBody List<Integer> memberIds) {
		log.info("Employee request to distribute coupon {} to member list: {}", couponId, memberIds);
		int count = couponService.distributeToMembers(couponId, memberIds);
		return ResponseEntity
				.ok(Map.of("success", true, "distributedCount", count, "message", "成功發放給 " + count + " 位會員"));
	}
}
