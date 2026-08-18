package tw.com.ispan.gym.repository.member;


import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Integer> {

	Optional<MemberCoupon> findByMemberIdAndCouponCouponIdAndStatus(Integer memberId, Integer couponId, MemberCouponStatus status);

	@Query("select mc from MemberCoupon mc where mc.member.id = :memberId")
	List<MemberCoupon> findByMemberId(@Param("memberId") Integer memberId);

	@Query("select mc from MemberCoupon mc where mc.member.id = :memberId and mc.status = :status")
	List<MemberCoupon> findByMemberIdAndStatus(@Param("memberId") Integer memberId, @Param("status") MemberCouponStatus status);

	@Query("select count(mc) > 0 from MemberCoupon mc where mc.member.id = :memberId and mc.coupon.couponId = :couponId")
	boolean existsByMemberIdAndCouponId(@Param("memberId") Integer memberId, @Param("couponId") Integer couponId);
}
