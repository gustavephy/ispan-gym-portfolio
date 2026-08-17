package tw.com.ispan.gym.repository.member;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Integer> {

	// 給cart使用
	Optional<MemberCoupon> findByMemberMemberIdAndCouponCouponIdAndStatus(Integer memberId, Integer couponId,
			MemberCouponStatus status);

	// 查詢會員所有優惠券清單
	@Query("select mc from MemberCoupon mc where mc.member.id = :memberId")
	List<MemberCoupon> findByMemberId(
			@Param("memberId") Integer memberId);

	// 依使用狀態（如未使用、已使用）查詢會員擁有的優惠券
	@Query("select mc from MemberCoupon mc where mc.member.id = :memberId and mc.status = :status")
	List<MemberCoupon> findByMemberIdAndStatus(
			@Param("memberId") Integer memberId,
			@Param("status") MemberCouponStatus status);

	// 檢查會員是否已領取過特定的優惠券（防止重複領取）
	@Query("select count(mc) > 0 from MemberCoupon mc where mc.member.id = :memberId and mc.coupon.couponId = :couponId")
	boolean existsByMemberIdAndCouponId(
			@Param("memberId") Integer memberId,
			@Param("couponId") Integer couponId);
}
