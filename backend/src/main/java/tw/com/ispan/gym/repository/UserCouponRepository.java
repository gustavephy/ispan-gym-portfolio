package tw.com.ispan.gym.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.MemberCoupon;
import tw.com.ispan.gym.enums.MemberCouponStatus;

public interface UserCouponRepository extends JpaRepository<MemberCoupon, Integer> {

	Optional<MemberCoupon> findByUserIdAndCouponIdAndStatus(Integer userId, Integer couponId, MemberCouponStatus status);
}
