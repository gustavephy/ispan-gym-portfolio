package tw.com.ispan.gym.controller.employee;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.CourseCategoryDto;
import tw.com.ispan.gym.service.course.CourseCategoryService;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/employee/course-categories")
@PreAuthorize("hasRole('EMPLOYEE')")
public class EmployeeCourseCategoryController {
    private final CourseCategoryService courseCategoryService;

    // 新增課程分類
    @PostMapping
    public ResponseEntity<CourseCategoryDto> createCourseCategory(@RequestBody CourseCategoryDto dto) {
        log.info("request to create course category:{}", dto.name());
        return ResponseEntity.ok(courseCategoryService.createCourseCategory(dto));
    }

    // 更新課程分類
    @PostMapping("/{id}")
    public ResponseEntity<CourseCategoryDto> updateCourseCategory(
            @PathVariable Integer id,
            @RequestBody CourseCategoryDto dto) {
        log.info("request to update course category: id={}", id);
        return ResponseEntity.ok(courseCategoryService.updateCourseCategory(id, dto));
    }

    // 刪除課程分類
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteCourseCategory(@PathVariable Integer id) {
        log.info("request to delete course category: id={}", id);
        courseCategoryService.deleteCourseCategory(id);
        return ResponseEntity.ok("課程分類已刪除");
    }
}
