package tw.com.ispan.gym.service.course;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.dto.course.GroupCourseRequest;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.repository.course.CourseCategoryRepository;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

@RequiredArgsConstructor
@Service
@Slf4j
@Transactional(readOnly = true)
public class GroupCourseService {

    private final GroupCourseRepository groupCourseRepository;
    private final CourseCategoryRepository courseCategoryRepository;
    private final TrainerRepository trainerRepository;

    // 取得團課清單(分頁，員工/後台管理用)
    public Page<GroupCourseResponse> getAllCourses(String keyword, Integer trainerId, CourseStatus status,
            LocalDate startDate,
            LocalDate endDate, int page, int pageSize) {
        PageRequest pageRequest = PageRequest.of(page, pageSize, Sort.by("groupCourseId").descending());
        Page<GroupCourse> courses = groupCourseRepository.findCoursesWithFilters(
                status,
                trainerId,
                (keyword != null && !keyword.isBlank()) ? keyword.trim() : null,
                startDate != null ? startDate.atStartOfDay() : null,
                endDate != null ? endDate.atTime(23, 59, 59, 999999) : null,
                pageRequest);
        return courses.map(GroupCourseResponse::from);
    }

    public GroupCourseResponse getGroupCourseById(Integer groupCourseId) {
        GroupCourse course = groupCourseRepository.findById(groupCourseId)
                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
        return GroupCourseResponse.from(course);
    }

    // 新增團課
    @Transactional
    public GroupCourseResponse createGroupCourse(GroupCourseRequest request) {
        if (request.trainerId() == null) {
            throw new BusinessRuleException("必須指定指導教練");
        }

        Trainer trainer = trainerRepository.findById(request.trainerId())
                .orElseThrow(() -> new ResourceNotFoundException("指定的教練不存在"));

        GroupCourse course = new GroupCourse();
        course.setTrainer(trainer);
        applyRequest(course, request);

        GroupCourse saved = groupCourseRepository.save(course);
        log.info("Employee successfully created group course (ID: {}, name: {}) for Trainer (ID: {})",
                saved.getGroupCourseId(), saved.getName(), trainer.getId());
        return GroupCourseResponse.from(saved);
    }

    // 更新團課
    @Transactional
    public GroupCourseResponse updateGroupCourse(Integer groupCourseId, GroupCourseRequest request) {
        GroupCourse course = groupCourseRepository.findById(groupCourseId)
                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));

        if (request.trainerId() != null) {
            Trainer trainer = trainerRepository.findById(request.trainerId())
                    .orElseThrow(() -> new ResourceNotFoundException("指定的教練不存在"));
            course.setTrainer(trainer);
        }

        applyRequest(course, request);

        GroupCourse saved = groupCourseRepository.save(course);
        log.info("Employee successfully updated group course (ID: {})", saved.getGroupCourseId());
        return GroupCourseResponse.from(saved);
    }

    // 下架團課
    @Transactional
    public void deleteGroupCourse(Integer groupCourseId) {
        log.info("request to delete group course: {}", groupCourseId);
        GroupCourse groupCourse = groupCourseRepository.findById(groupCourseId)
                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
        groupCourse.setStatus(CourseStatus.INACTIVE);
        groupCourseRepository.save(groupCourse);
    }

    // 內用方法，提供給createCourse和updateGroupCourse使用，不對外開放
    private void applyRequest(GroupCourse groupCourse, GroupCourseRequest request) {
        groupCourse.setName(request.name());
        groupCourse.setMaxCapacity(request.maxCapacity() != null ? request.maxCapacity() : 20);
        groupCourse.setDescription(request.description());
        groupCourse.setImageUrl(request.imageUrl());
        groupCourse.setIsTrial(request.isTrial() != null ? request.isTrial() : false);
        groupCourse.setStatus(request.status() != null ? request.status() : CourseStatus.ACTIVE);

        if (request.courseCategoryId() != null) {
            CourseCategory cat = courseCategoryRepository.findById(request.courseCategoryId()).orElse(null);
            groupCourse.setCategory(cat);
        }
    }
}
