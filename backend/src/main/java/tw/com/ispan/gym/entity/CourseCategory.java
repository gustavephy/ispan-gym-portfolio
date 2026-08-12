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

/** 課程分類；與商品分類（product_categories）各自獨立 */
@Entity
@Table(name = "course_category")
@Getter
@Setter
public class CourseCategory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "course_category_id")
	private Integer courseCategoryId;

	@Column(nullable = false, columnDefinition = "NVARCHAR(20)")
	private String name;

	@Column(nullable = false)
	private Integer sortOrder = 0;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "VARCHAR(20)")
	private CategoryStatus status = CategoryStatus.ACTIVE;
}
