package tw.com.ispan.gym.controller.employee;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.CourseCategoryDto;
import tw.com.ispan.gym.service.course.CourseCategoryService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/employee/course-categories")
@Slf4j
@PreAuthorize("hasRole('EMPLOYEE')")
public class EmployeeCourseCategoryController {

    private final CourseCategoryService courseCategoryService;

    // 新增課程分類
    @PostMapping
    public ResponseEntity<CourseCategoryDto> createCourseCategory(@RequestBody CourseCategoryDto category) {
        log.info("Request to create course category: name={}", category.name());
        return ResponseEntity.ok(courseCategoryService.createCourseCategory(category));
    }

    // 刪除課程分類
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteCourseCategory(@PathVariable Integer courseCategoryId) {
        log.info("Request to delete course category: courseCategoryId={}", courseCategoryId);
        courseCategoryService.deleteCourseCategory(courseCategoryId);
        return ResponseEntity.ok("課程分類已刪除");
    }

    // 更新課程分類
    @PutMapping("/{id}")
    public ResponseEntity<CourseCategoryDto> updateCourseCategory(
            @PathVariable Integer courseCategoryId,
            @RequestBody CourseCategoryDto category) {
        log.info("Request to update course category: courseCategoryId={}, name={}", courseCategoryId, category.name());
        return ResponseEntity.ok(courseCategoryService.updateCourseCategory(courseCategoryId, category));
    }
}
