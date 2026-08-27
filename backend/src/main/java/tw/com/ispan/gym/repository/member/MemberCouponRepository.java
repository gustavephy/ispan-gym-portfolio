package tw.com.ispan.gym.repository.member;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Integer> {

	// 檢查是否領過
	Optional<MemberCoupon> findByMemberIdAndCouponCouponIdAndStatus(
			Integer memberId,
			Integer couponId,
			MemberCouponStatus status);

	// 查詢個人所有優惠券（不包含使用狀態）
	@Query("select mc from MemberCoupon mc where mc.member.id = :memberId")
	Page<MemberCoupon> findByMemberId(
			@Param("memberId") Integer memberId,
			Pageable pageable);

	// 給結帳的時候使用，搜尋所有可用優惠券（未過期、未使用、未逾期）
	@Query("select mc from MemberCoupon mc where mc.member.id = :memberId and mc.status = :status")
	Page<MemberCoupon> findByMemberIdAndStatus(
			@Param("memberId") Integer memberId,
			@Param("status") MemberCouponStatus status,
			Pageable pageable);

	// 購物車結帳流程
	@Query("select count(mc) > 0 from MemberCoupon mc where mc.member.id = :memberId and mc.coupon.couponId = :couponId")
	boolean existsByMemberIdAndCouponId(
			@Param("memberId") Integer memberId,
			@Param("couponId") Integer couponId);
}
