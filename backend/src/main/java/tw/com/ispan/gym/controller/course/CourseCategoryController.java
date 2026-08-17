package tw.com.ispan.gym.controller.course;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
	// 【合併時的修改說明】三個寫入端點的參數原本沒加 @RequestBody／@PathVariable：
	// 少了註解時 Spring 不會從 JSON body 組 dto（欄位全 null）、路徑上的 {id} 也接不到（id=null），
	// 端點一打就 NPE 或刪錯資料，所以補上綁定註解；路由與方法名都維持原樣。
	@PostMapping("/api/employee/course-categories")
	@PreAuthorize("hasRole('EMPLOYEE')")
	public ResponseEntity<CourseCategoryDto> createCourseCategory(@RequestBody CourseCategoryDto dto){
		log.info("Request to create course category: name={}", dto.name());
		return ResponseEntity.ok(courseCategoryService.createCourseCategory(dto));
	}

	//刪除課程分類
	@DeleteMapping("/api/employee/course-categories/{id}")
	@PreAuthorize("hasRole('EMPLOYEE')")
	public ResponseEntity<String> deleteCourseCategory(@PathVariable Integer id){
		log.info("Request to delete course category with id={}", id);
		courseCategoryService.deleteCourseCategory(id);
		return ResponseEntity.ok("課程分類已刪除");
	}

	//更新課程分類
	@PostMapping("/api/employee/course-categories/{id}")
	@PreAuthorize("hasRole('EMPLOYEE')")
	public ResponseEntity<CourseCategoryDto> updateCourseCategory(@PathVariable Integer id,@RequestBody CourseCategoryDto dto){
		log.info("Request to update course category with id={}, name={}", id, dto.name());
		return ResponseEntity.ok(courseCategoryService.updateCourseCategory(id, dto));
	}
	
}
