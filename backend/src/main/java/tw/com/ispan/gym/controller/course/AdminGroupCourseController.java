package tw.com.ispan.gym.controller.course;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.course.GroupCourseRequest;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.service.course.GroupCourseService;

/**
 * 後台團課主檔管理。受 SecurityConfig 既有的 /api/admin/** → hasRole("ADMIN") 規則保護，
 * 這裡額外疊上 @PreAuthorize，跟 AdminGroupClassController（場次管理）的慣例一致（雙層防護）。
 */
@RestController
@RequestMapping("/api/admin/group-courses")
@RequiredArgsConstructor
public class AdminGroupCourseController {

    private final GroupCourseService groupCourseService;

    /** 建立團課主檔，成功回 201。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GroupCourseResponse> createCourse(@Valid @RequestBody GroupCourseRequest request) {
        GroupCourseResponse resp = groupCourseService.createCourse(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /** 查詢所有團課主檔，不分狀態，成功回 200。 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<GroupCourseResponse>> getAllCourses() {
        List<GroupCourseResponse> resp = groupCourseService.getAllCourses();
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

    /** 下架團課主檔，成功回 200（更新既有資源的狀態，不是新建立）。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GroupCourseResponse> deactivateCourse(@PathVariable Integer id) {
        GroupCourseResponse resp = groupCourseService.deactivateCourse(id);
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }
}
