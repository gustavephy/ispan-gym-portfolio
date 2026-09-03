package tw.com.ispan.gym.repository.coupon;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.enums.coupon.CouponStatus;

public interface CouponRepository extends JpaRepository<Coupon, Integer> {
	// 依代碼搜尋優惠券
	Optional<Coupon> findByCode(String code);

	// 用於前台展示可領取的優惠券
	Page<Coupon> findByStatus(CouponStatus status, Pageable pageable);

	// 悲觀鎖，用於使用者搶購優惠券時，確保高併發的情況下，防止超賣
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM Coupon c WHERE c.couponId =:couponId")
	Optional<Coupon> findByIdForUpdate(@Param("couponId") Integer couponId);

	/**
	 * 優惠券成效聚合（營運報表用）：各券的領取與使用數。
	 * LEFT JOIN 讓沒人領過的券也產生列；成效看整個生命週期，不做期間篩選。
	 */
	@Query("""
			SELECT new tw.com.ispan.gym.dto.report.CouponEffectRow(
			    c.name, c.code, c.totalQuantity, c.claimedQuantity,
			    SUM(CASE WHEN mc.status = 'USED' THEN 1L ELSE 0L END))
			FROM Coupon c
			LEFT JOIN MemberCoupon mc ON mc.coupon = c
			GROUP BY c.couponId, c.name, c.code, c.totalQuantity, c.claimedQuantity
			ORDER BY c.claimedQuantity DESC
			""")
	List<tw.com.ispan.gym.dto.report.CouponEffectRow> aggregateEffect();
}
