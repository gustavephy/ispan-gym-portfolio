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
}
