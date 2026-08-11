package tw.com.ispan.gym.entity;


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
@Table(name = "user_coupons")
@Getter
@Setter
@ToString(exclude = {"user","coupon"})
@EntityListeners(AuditingEntityListener.class)
public class MemberCoupon {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

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
