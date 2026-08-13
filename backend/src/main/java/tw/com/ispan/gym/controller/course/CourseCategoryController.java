package tw.com.ispan.gym.controller.course;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.CourseCategoryDto;
import tw.com.ispan.gym.service.course.CourseCategoryService;

@RestController
@RequiredArgsConstructor
@Slf4j
public class CourseCategoryController {
	
	private final CourseCategoryService courseCategoryService;
	
	//查詢課程所有分類
	@GetMapping("/api/course-categories")
	public ResponseEntity<List<CourseCategoryDto>> getCourseCategories(){
		log.info("Request to get all course categories");
		return ResponseEntity.ok(courseCategoryService.getAllCourseCategories());
	}
	
	//新增課程分類
	@PostMapping("/api/employee/course-categories")
	@PreAuthorize("hasRole('EMPLOYEE')")
	public ResponseEntity<CourseCategoryDto> createCourseCategory(CourseCategoryDto dto){
		log.info("Request to create course category: name={}", dto.getName());
		return ResponseEntity.ok(courseCategoryService.createCourseCategory(dto));
	}
	
	//刪除課程分類
	@DeleteMapping("/api/employee/course-categories/{id}")
	@PreAuthorize("hasRole('EMPLOYEE')")
	public ResponseEntity<String> deleteCourseCategory(Integer id){
		log.info("Request to delete course category with id={}", id);
		courseCategoryService.deleteCourseCategory(id);
		return ResponseEntity.ok("課程分類已刪除");
	}
	
	//更新課程分類
	@PostMapping("/api/employee/course-categories/{id}")
	@PreAuthorize("hasRole('EMPLOYEE')")
	public ResponseEntity<CourseCategoryDto> updateCourseCategory(Integer id,CourseCategoryDto dto){
		log.info("Request to update course category with id={}, name={}", id, dto.getName());
		return ResponseEntity.ok(courseCategoryService.updateCourseCategory(id, dto));
	}
	
}
