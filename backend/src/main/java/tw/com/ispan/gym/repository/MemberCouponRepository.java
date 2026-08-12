package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.MemberCoupon;
import tw.com.ispan.gym.enums.MemberCouponStatus;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Integer> {
	List<MemberCoupon> findByMemberId(Integer memberId);
	
	List<MemberCoupon> findByMemberIdAndStatus(Integer memberId, MemberCouponStatus status);
	
	boolean existsByMemberIdAndCouponId(Integer memberId, Integer couponId);
}
