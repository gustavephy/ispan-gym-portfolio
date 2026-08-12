package tw.com.ispan.gym.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.MemberCoupon;
import tw.com.ispan.gym.enums.MemberCouponStatus;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Integer> {

	Optional<MemberCoupon> findByMemberMemberIdAndCouponCouponIdAndStatus(Integer userId, Integer couponId, MemberCouponStatus status);
}
