package tw.com.ispan.gym.controller.course;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.service.course.GroupCourseService;

@RestController
@RequestMapping("/api/group-courses")
@RequiredArgsConstructor
@Slf4j
public class GroupCourseController {
	private final GroupCourseService groupCourseService;

	// 商城專用
	@GetMapping("/public")
	public ResponseEntity<List<GroupCourseResponse>> getPublicCourses(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) Integer trainerId,
			@RequestParam(required = false) Integer categoryId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
		log.info(
				"Public request to get group courses: keyword={}, trainerId={}, categoryId={}, startDate={}, endDate={}",
				keyword, trainerId, categoryId, startDate, endDate);
		return ResponseEntity.ok(groupCourseService.getPublicCourses(keyword, trainerId, categoryId, startDate,
				endDate));
	}

}
