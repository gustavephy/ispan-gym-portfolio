package tw.com.ispan.gym.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tw.com.ispan.gym.enums.CategoryStatus;

@Entity
@Table(name="product_categories")
@Getter
@Setter
public class ProductCategory {
	
	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	@Column(name = "product_category_id")
	private Integer productCategoryId;
	
	@Column(nullable=false, columnDefinition="NVARCHAR(20)")
	private String name;
	
	@Column(nullable=false)
	private Integer sortOrder=0;
	
	@Enumerated(EnumType.STRING)
	@Column(nullable=false, columnDefinition="VARCHAR(20)")
	private CategoryStatus status = CategoryStatus.ACTIVE;
}
