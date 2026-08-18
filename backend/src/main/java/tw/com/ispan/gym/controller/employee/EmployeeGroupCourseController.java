package tw.com.ispan.gym.controller.employee;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.GroupCourseRequest;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.service.course.GroupCourseService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/employee/group-courses")
@PreAuthorize("hasRole('Employee')")
@Slf4j
public class EmployeeGroupCourseController {

    private final GroupCourseService groupCourseService;

    // 取得所有團課(分頁)
    @GetMapping
    public ResponseEntity<Page<GroupCourseResponse>> getAllCourses(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) CourseStatus status,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Request to get group courses: keyword={}, status={}, startDate={}, endDate={}, page={}, size={}",
                keyword, status, startDate, endDate, page, size);
        return ResponseEntity
                .ok(groupCourseService.getAllCourses(keyword, null, status, startDate, endDate, page, size));
    }

    // 取得團課資訊by Id
    @GetMapping("/{groupCourseId}")
    public ResponseEntity<GroupCourseResponse> getCourseById(@PathVariable Integer groupCourseId) {
        log.info("Request to get group course details by employee, groupCourseId={}", groupCourseId);
        return ResponseEntity.ok(groupCourseService.getGroupCourseById(groupCourseId));
    }

    @PostMapping
    public ResponseEntity<GroupCourseResponse> createCourse(@RequestBody GroupCourseRequest request) {
        log.info("Request to create group course by employee: name={}", request.name());
        return ResponseEntity.ok(groupCourseService.createGroupCourse(request));
    }

    @PutMapping("/{groupCourseId}")
    public ResponseEntity<GroupCourseResponse> updateCourse(@PathVariable Integer groupCourseId,
            @RequestBody GroupCourseRequest request) {
        log.info("Request to update group course by employee, groupCourseId={}, name={}", groupCourseId,
                request.name());
        return ResponseEntity.ok(groupCourseService.updateGroupCourse(groupCourseId, request));
    }

    @DeleteMapping("/{groupCourseId}")
    public ResponseEntity<Void> deleteCourse(@PathVariable Integer groupCourseId) {
        log.info("Request to deactivate group course by employee, groupCourseId={}", groupCourseId);
        groupCourseService.deleteGroupCourse(groupCourseId);
        return ResponseEntity.noContent().build();
    }

}
