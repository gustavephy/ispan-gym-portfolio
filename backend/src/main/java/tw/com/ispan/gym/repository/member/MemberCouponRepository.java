package tw.com.ispan.gym.repository.member;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Integer> {
	List<MemberCoupon> findByMemberId(Integer memberId);
	
	List<MemberCoupon> findByMemberIdAndStatus(Integer memberId, MemberCouponStatus status);
	
	boolean existsByMemberIdAndCouponId(Integer memberId, Integer couponId);
}
