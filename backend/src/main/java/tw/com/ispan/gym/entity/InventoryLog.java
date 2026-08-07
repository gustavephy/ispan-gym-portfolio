package tw.com.ispan.gym.entity;

import tw.com.ispan.gym.user.entity.User;
import tw.com.ispan.gym.order.entity.Order;

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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "inventory_log")
@Data
@EntityListeners(AuditingEntityListener.class)
public class InventoryLog {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	@ManyToOne
	@JoinColumn(name="product_sku_id")
	private ProductSku productSku;
	
	//INCOMING, OUTGOING, ADJUSTMENT
	@Column(nullable=false, length=20)
	private String changeType; 
	
	@Column(nullable=false)
	private Integer quantity;
	
	@Column(length=500, columnDefinition="NVARCHAR(500)")
	private String description;
	
	@Column(name="balance_after")
	private Integer balanceAfter;
	
	@ManyToOne
	@JoinColumn(name="ref_order_id")
	private Order refOrder;
	
	@ManyToOne
	@JoinColumn(name="operator_id")
	private User oprerator;
	
	@CreatedDate
	@Column(updatable=false,columnDefinition="datetime2")
	private LocalDateTime createdAt;
	
	@LastModifiedDate
	@Column(columnDefinition="datetime2")
	private LocalDateTime updatedAt;
}

