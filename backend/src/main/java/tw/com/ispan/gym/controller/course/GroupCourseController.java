package tw.com.ispan.gym.controller.course;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.service.course.GroupCourseService;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/group-courses")
public class GroupCourseController {
    private final GroupCourseService groupCourseService;

    // 查詢團體課程（提供前台商城供會員瀏覽與選購）
    @GetMapping("/public")
    public ResponseEntity<List<GroupCourseResponse>> getPublicCourses() {
        return ResponseEntity.ok(groupCourseService.getPublicCourses());
    }

}
