package tw.com.ispan.gym.entity.coupon;

import tw.com.ispan.gym.entity.member.Member;


import java.time.LocalDateTime;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import tw.com.ispan.gym.enums.MemberCouponStatus;

@Entity
@Table(name = "member_coupon")
@Getter
@Setter
@ToString(exclude = {"member","coupon"})
@EntityListeners(AuditingEntityListener.class)
public class MemberCoupon {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "member_coupon_id")
	private Integer memberCouponId;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "coupon_id", nullable = false)
	private Coupon coupon;

	@Enumerated(EnumType.STRING)
	@Column(columnDefinition = "VARCHAR(20)")
	private MemberCouponStatus status = MemberCouponStatus.UNUSED;

	@Column(columnDefinition = "datetime2")
	private LocalDateTime claimedAt;

	@Column(columnDefinition = "datetime2")
	private LocalDateTime usedAt;
}
