package tw.com.ispan.gym.controller.course;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.CourseCategoryDto;
import tw.com.ispan.gym.service.course.CourseCategoryService;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/course-categories")
public class CourseCategoryController {

	private final CourseCategoryService courseCategoryService;

	// 查詢課程所有分類
	@GetMapping
	public ResponseEntity<List<CourseCategoryDto>> getCourseCategories() {
		log.info("Request to get all course categories");
		return ResponseEntity.ok(courseCategoryService.getAllCourseCategories());
	}
}
