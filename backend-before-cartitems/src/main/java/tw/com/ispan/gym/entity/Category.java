package tw.com.ispan.gym.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name="categories")
public class Category {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	@Column(nullable=false, length=20, columnDefinition="NVARCHAR(20)")
	private String name;
	
	//分類:PHYSICAL(實體商品)/COURSE(課程)/SUBSCRIPTION(訂閱)
	@Column(nullable=false, length=20)
	private String categoryType;
	
	@Column(columnDefinition="NVARCHAR(MAX)")
	private String iconUrl;
	
	@Column(nullable=false)
	private Integer sortOrder=0;
	
	@Column(nullable=false, length=20, columnDefinition="VARCHAR(20)")
	private String status = "ACTIVE";
}
