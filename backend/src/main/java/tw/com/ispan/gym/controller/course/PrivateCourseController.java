package tw.com.ispan.gym.controller.course;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.PrivateCourseResponse;
import tw.com.ispan.gym.service.course.PrivateCourseService;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/private-courses")
public class PrivateCourseController {
    private final PrivateCourseService privateCourseService;

    // 查詢私教課程（提供前台商城供會員瀏覽與選購）
    @GetMapping("/public")
    public ResponseEntity<List<PrivateCourseResponse>> getPublicCourses() {
        return ResponseEntity.ok(privateCourseService.getPublicCourses());
    }

}
