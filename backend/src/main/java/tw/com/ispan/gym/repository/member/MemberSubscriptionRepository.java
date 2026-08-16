package tw.com.ispan.gym.repository.member;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;

/**
 * 【合併時的修改說明】這支取代 dev 上的 repository/subscription/SubscriptionRepository：
 * 類名與位置採 feat/coupon-controller 的設計（會員名下的資料放 member 套件、
 * 名稱冠 MemberSubscription 與實體對齊）；方法取兩邊聯集——
 * findByMemberId 是 dev 端訂閱結帳（OrderService）在用的，不能少；
 * findByMemberIdOrderByEndDateDesc 是分支端新增的排序版查詢，一併保留。
 */
public interface MemberSubscriptionRepository extends JpaRepository<MemberSubscription, Integer> {

	List<MemberSubscription> findByMemberId(Integer memberId);

	List<MemberSubscription> findByMemberIdOrderByEndDateDesc(Integer memberId);

	Optional<MemberSubscription> findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(Integer memberId,
			LocalDateTime now, SubscriptionStatus status);

	/** 退款時反查該筆訂閱訂單建立的那期訂閱，見 OrderService#completeRefund */
	Optional<MemberSubscription> findByOrderId(Long orderId);
}
