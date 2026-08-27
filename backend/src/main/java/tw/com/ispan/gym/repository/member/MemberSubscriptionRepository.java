package tw.com.ispan.gym.repository.member;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	@Query("SELECT ms FROM MemberSubscription ms WHERE ms.order.orderId = :orderId")
	Optional<MemberSubscription> findByOrderId(@Param("orderId") Integer orderId);

	/** 期間新生效訂閱數（報表用）；半開區間 [from, to)。訂閱於付款履約時建立，createdAt＝生效日 */
	@Query("SELECT COUNT(ms) FROM MemberSubscription ms WHERE ms.createdAt >= :from AND ms.createdAt < :to")
	long countCreatedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
