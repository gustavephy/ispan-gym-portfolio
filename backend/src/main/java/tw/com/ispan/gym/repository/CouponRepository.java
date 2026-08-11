package tw.com.ispan.gym.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import tw.com.ispan.gym.entity.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, Integer> {
	Optional<Coupon> findByCode(String code);
	List<Coupon> findByStatus(CouponStatus status);
	
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM Coupon c WHERE c.couponId =:couponId")
	Optional<Coupon> findByIdForUpdate(@Param("couponId")Integer couponId);
}
