package tw.com.ispan.gym.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.UserCoupon;
import tw.com.ispan.gym.enums.UserCouponStatus;

public interface UserCouponRepository extends JpaRepository<UserCoupon, Integer> {
//	List<UserCoupon> findByUserId(Integer userId);

	Optional<UserCoupon> findByUserIdAndCouponIdAndStatus(Integer userId, Integer couponId, UserCouponStatus status);
}
