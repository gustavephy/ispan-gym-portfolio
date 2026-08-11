package tw.com.ispan.gym.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import tw.com.ispan.gym.enums.SubscriptionStatus;

@Entity
@Table(name = "subscription")
@Getter
@Setter
@ToString(exclude = {"user","order"})
@EntityListeners(AuditingEntityListener.class)
public class Subscription {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;
	
	@Column(nullable=false, columnDefinition="NVACHAR(100)")
	private String planName;
	
	@ManyToOne(optional=false, fetch=FetchType.LAZY)
	@JoinColumn(name="subscription_plan_id",nullable=false)
	private SubscriptionPlan subscriptionPlan;
	
	@Column(nullable = false)
	private LocalDateTime startDate;

	@Column(nullable = false)
	private LocalDateTime endDate;

	@Column(nullable = false)
	@Enumerated(EnumType.STRING)
	private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

	@CreatedDate
	@Column(updatable = false, columnDefinition = "datetime2")
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime updatedAt;
}
