package tw.com.ispan.gym.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import tw.com.ispan.gym.enums.CategoryStatus;

@Entity
@Table(name = "categories")
@Getter
@Setter
@ToString
public class Category {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@Column(nullable = false, length = 20, columnDefinition = "NVARCHAR(20)")
	private String name;

	@Column(nullable = false, length = 20)
	private String categoryType;

	@Column(columnDefinition = "NVARCHAR(MAX)")
	private String iconUrl;

	@Column(nullable = false)
	private Integer sortOrder = 0;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
	private CategoryStatus status = CategoryStatus.ACTIVE;
}
