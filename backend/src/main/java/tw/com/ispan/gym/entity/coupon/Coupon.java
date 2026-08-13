package tw.com.ispan.gym.entity.coupon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.coupon.CouponType;

@Entity
@Table(name = "coupon", uniqueConstraints = @UniqueConstraint(name = "UX_coupons_code", columnNames = "code"))
@Getter
@Setter
@ToString
@EntityListeners(AuditingEntityListener.class)
public class Coupon {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "coupon_id")
	private Integer couponId;
	
	@Column(nullable = false, length = 50, columnDefinition="VARCHAR(50)")
	private String code;
	
	@Column(nullable = false, length = 50, columnDefinition="NVARCHAR(50)")
	private String name;
	
	@Column(length = 500, columnDefinition="NVARCHAR(500)")
	private String description;
	
	@Column(nullable = false, length = 20, columnDefinition="VARCHAR(20)")
	@Enumerated(EnumType.STRING)
	private CouponType type = CouponType.CASH;
	
	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal value;
	
	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal minSpend = BigDecimal.ZERO;
	
	@Column(nullable = false)
	private Integer totalQuantity = 0;
	
	@Column(nullable = false)
	private Integer claimedQuantity = 0;
	
	@Column(nullable = false)
	private LocalDate startDate;
	
	@Column(nullable = false)
	private LocalDate endDate;
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, columnDefinition="VARCHAR(20)")
	private CouponStatus status = CouponStatus.ACTIVE;
	
	@Column(nullable = false, length = 20, columnDefinition="VARCHAR(20)")
	@Enumerated(EnumType.STRING)
	private CouponApplicableType applicableType = CouponApplicableType.ALL;
	
	@CreatedDate
	@Column(updatable = false,columnDefinition="datetime2")
	private LocalDateTime createdAt;
	
	@LastModifiedDate
	private LocalDateTime updatedAt;

}
