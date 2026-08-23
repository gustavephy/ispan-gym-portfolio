package tw.com.ispan.gym.controller.course;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.PrivateCourseResponse;
import tw.com.ispan.gym.service.course.PrivateCourseService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/private-courses")
@Slf4j
public class PrivateCourseController {

        private final PrivateCourseService privateCourseService;

        // 商城專用，查詢所有私教課方案
        @GetMapping("/public")
        public ResponseEntity<List<PrivateCourseResponse>> getPublicCourses(
                        @RequestParam(required = false) String keyword,
                        @RequestParam(required = false) Integer trainerId,
                        @RequestParam(required = false) Integer categoryId) {
                log.info("Public request to get private courses list: keyword={}, trainerId={}, categoryId={}",
                                keyword, trainerId, categoryId);
                return ResponseEntity.ok(privateCourseService.getPublicCourses(keyword, trainerId, categoryId));
        }

        // 根據 ID 查詢私教課方案
        @GetMapping("/public/{id}")
        public ResponseEntity<PrivateCourseResponse> getPublicCourseById(
                        @PathVariable Integer id) {
                log.info("Public request to get private course details: id={}", id);
                return ResponseEntity.ok(privateCourseService.getPublicCourseById(id));
        }

}
