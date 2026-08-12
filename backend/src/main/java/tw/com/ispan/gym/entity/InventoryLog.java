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
import tw.com.ispan.gym.enums.InventoryChangeType;

@Entity
@Table(name = "inventory_log")
@Getter
@Setter
@ToString(exclude={"product","refOrder","operator"})
@EntityListeners(AuditingEntityListener.class)
public class InventoryLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "inventory_log_id")
	private Integer inventoryLogId;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "product_id")
	private Product product;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private InventoryChangeType changeType;

	@Column(nullable = false)
	private Integer quantity;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String description;

	@Column(name = "balance_after")
	private Integer balanceAfter;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "ref_order_id")
	private Order refOrder;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "operator_id")
	private User operator;

	@CreatedDate
	@Column(updatable = false, columnDefinition = "datetime2")
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime updatedAt;
}
