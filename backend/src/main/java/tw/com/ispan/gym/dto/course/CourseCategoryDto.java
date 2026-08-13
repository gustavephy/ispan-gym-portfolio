package tw.com.ispan.gym.dto.course;


import lombok.Data;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.enums.common.CategoryStatus;

@Data	
public class CourseCategoryDto {
	private Integer courseCategoryId;
	private String name;
	private Integer sortOrder;
	private CategoryStatus status;
	
	public static CourseCategoryDto fromEntity(CourseCategory courseCategory) {
		CourseCategoryDto dto = new CourseCategoryDto();
		dto.setCourseCategoryId(courseCategory.getCourseCategoryId());
		dto.setName(courseCategory.getName());
		dto.setSortOrder(courseCategory.getSortOrder());
		dto.setStatus(courseCategory.getStatus() != null ? courseCategory.getStatus() : CategoryStatus.ACTIVE);
		return dto;
	}
	
}
