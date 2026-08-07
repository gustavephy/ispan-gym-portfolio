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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name="user_coupons")
@Data
@EntityListeners(AuditingEntityListener.class)
public class UserCoupon {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	@ManyToOne
	@JoinColumn(name = "user_id", nullable = false)
	private User user;
	
	@ManyToOne
	@JoinColumn(name = "coupon_id", nullable = false)
	private Coupon coupon;
	
	//狀態: UNUSED(未使用)/USED(已使用)/EXPIRED(已過期)
	private String status="UNUSED";
	
	@Column(columnDefinition = "datetime2")
	private LocalDateTime claimedAt;
	
	@Column(columnDefinition = "datetime2")
	private LocalDateTime usedAt;
}
