package tw.com.ispan.gym.controller.course;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.course.PrivateCourseResponse;
import tw.com.ispan.gym.service.course.PrivateCourseService;

/**
 * 公開瀏覽私教課程。
 * 供前台「課程方案」頁面展示與購買使用，免登入。
 * SecurityConfig 已將 GET /api/private-courses/** 設為 permitAll。
 */
@RestController
@RequestMapping("/api/private-courses")
@RequiredArgsConstructor
public class PrivateCourseController {

    private final PrivateCourseService privateCourseService;

    /** 取得所有上架中（ACTIVE）的私教課方案清單 */
    @GetMapping
    public ResponseEntity<List<PrivateCourseResponse>> getPublicCourses() {
        List<PrivateCourseResponse> courses = privateCourseService.getPublicCourses();
        return ResponseEntity.status(HttpStatus.OK).body(courses);
    }
}
