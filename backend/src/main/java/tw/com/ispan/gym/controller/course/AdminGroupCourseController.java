package tw.com.ispan.gym.controller.course;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.dto.course.GroupCourseRequest;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.dto.course.UpdateGroupCourseRequest;
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

    /** 分頁查詢所有團課主檔，不分狀態，可依課程名稱或授課教練姓名搜尋，成功回 200。 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<GroupCourseResponse>> getAllCourses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search) {
        PageResponse<GroupCourseResponse> resp = groupCourseService.getAllCourses(page, size, search);
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

    /** 修改團課主檔，成功回 200。授課教練與狀態不可經此端點變更。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GroupCourseResponse> updateCourse(@PathVariable Integer id,
            @Valid @RequestBody UpdateGroupCourseRequest request) {
        GroupCourseResponse resp = groupCourseService.updateCourse(id, request);
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
