package tw.com.ispan.gym.controller.course;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.PrivateCourseResponse;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.service.course.PrivateCourseService;

@RestController
@RequiredArgsConstructor
@RequestMapping
@Slf4j
public class PrivateCourseController {

    private final PrivateCourseService privateCourseService;

    // 商城專用
    @GetMapping("/public")
    public ResponseEntity<org.springframework.data.domain.Page<PrivateCourseResponse>> getPublicCourses(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer trainerId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Public request to get private courses: keyword={}, startDate={}, endDate={}, page={}, size={}",
                keyword, startDate, endDate, page, size);
        return ResponseEntity.ok(privateCourseService.getAllCourses(keyword, trainerId, CourseStatus.ACTIVE, startDate,
                endDate, page, size));
    }
}
