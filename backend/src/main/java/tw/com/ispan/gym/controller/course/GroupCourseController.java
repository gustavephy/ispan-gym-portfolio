package tw.com.ispan.gym.controller.course;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.service.course.GroupCourseService;

/**
 * 公開瀏覽團課主檔。刻意不加 @PreAuthorize（不限定要有 MEMBER 角色），
 * 跟 GroupClassController.getUpcomingSessions() 的既有取捨一致：瀏覽課程本身不該要求登入權限。
 * SecurityConfig 已對 {@code GET /api/group-courses} 補上 permitAll。
 */
@RestController
@RequestMapping("/api/group-courses")
@RequiredArgsConstructor
public class GroupCourseController {

    private final GroupCourseService groupCourseService;

    /** 分頁查詢上架中（status=ACTIVE）的團課主檔，可依課程名稱或教練姓名搜尋。 */
    @GetMapping
    public ResponseEntity<PageResponse<GroupCourseResponse>> getActiveCourses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer trainerId,
            @RequestParam(required = false) String search) {
        PageResponse<GroupCourseResponse> courses = groupCourseService.getActiveCoursesPaged(page, size, categoryId, trainerId, search);
        return ResponseEntity.status(HttpStatus.OK).body(courses);
    }
}
