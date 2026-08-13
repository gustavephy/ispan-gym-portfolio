package tw.com.ispan.gym.entity.product;

import java.math.BigDecimal;
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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import tw.com.ispan.gym.enums.product.ProductStatus;

@Entity
@Table(name = "product")
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "product_id")
	private Integer productId;

	@Column(nullable = false, length = 50, columnDefinition = "NVARCHAR(50)")
	private String name;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal price;

	@Column(columnDefinition = "NVARCHAR(500)")
	private String description;

	@Column(nullable = false)
	private Integer stock;

	@Version
	private Integer version = 0;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
	private ProductStatus status = ProductStatus.ACTIVE;

	@Column(columnDefinition = "NVARCHAR(1000)")
	private String imageUrl;

	@Column(length = 50, columnDefinition = "NVARCHAR(50)")
	private String brand;

	private Double weight;

	@Column(length = 50, columnDefinition = "NVARCHAR(50)")
	private String flavor;

	@Column(length = 50, columnDefinition = "NVARCHAR(50)")
	private String size;

	@Column(length = 50, columnDefinition = "NVARCHAR(50)")
	private String color;

	@Column(length = 50, columnDefinition = "NVARCHAR(50)")
	private String material;

	@Column(length = 50, columnDefinition = "NVARCHAR(50)")
	private String origin;

	@jakarta.persistence.ManyToOne
	@jakarta.persistence.JoinColumn(name = "category_id")
	private ProductCategory productCategory;

	@CreatedDate
	@Column(updatable = false, columnDefinition = "datetime2")
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime updatedAt;
}
