package tw.com.ispan.gym.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import tw.com.ispan.gym.enums.CategoryStatus;

public class CourseCategory {
	
	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	private Integer id;
	
	@Column(nullable=false)
	private String name;
	
	@Column(nullable=false)
	private Integer sortOrder=0;
	
	@Enumerated(EnumType.STRING)
	@Column(nullable=false, columnDefinition="VARCHAR(20)")
	private CategoryStatus status = CategoryStatus.ACTIVE;
}
