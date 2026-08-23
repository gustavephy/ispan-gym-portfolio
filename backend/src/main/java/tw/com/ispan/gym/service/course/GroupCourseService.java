package tw.com.ispan.gym.service.course;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    // 取得前台商城展示中的團體課程方案列表 (不分頁)
    public List<GroupCourseResponse> getPublicCourses(
            String keyword, Integer trainerId, Integer categoryId, LocalDate startDate, LocalDate endDate) {
        return getAllCourses(keyword, trainerId, categoryId, CourseStatus.ACTIVE, startDate, endDate, 0, 9999)
                .getContent();
    }

    private final GroupCourseRepository groupCourseRepository;
    private final CourseCategoryRepository categoryRepository;
    private final TrainerRepository trainerRepository;

    // 取得團體課程清單（分頁，員工/後台管理用）
    // status = null 表示全部（含下架），與 ProductService.getProducts 同格式
    public Page<GroupCourseResponse> getAllCourses(
            String keyword,
            Integer trainerId,
            Integer categoryId,
            CourseStatus status,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int pageSize) {
        PageRequest pageRequest = PageRequest.of(page, pageSize, Sort.by("groupCourseId").descending());

        LocalDateTime startLdt = null;
        if (startDate != null) {
            startLdt = startDate.atStartOfDay();
        }

        LocalDateTime endLdt = null;
        if (endDate != null) {
            endLdt = endDate.atTime(23, 59, 59, 999999);
        }

        Page<GroupCourse> courses = groupCourseRepository.findCoursesWithFilters(
                status,
                trainerId,
                (keyword != null && !keyword.isBlank()) ? keyword.trim() : null,
                startLdt,
                endLdt,
                pageRequest);
        return courses.map(GroupCourseResponse::from);
    }

    // 取得團體課程清單（分頁，員工/後台管理用 - 7參數重載）
    public Page<GroupCourseResponse> getAllCourses(
            String keyword,
            Integer trainerId,
            CourseStatus status,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int pageSize) {
        return getAllCourses(keyword, trainerId, null, status, startDate, endDate, page, pageSize);
    }

    // 根據 ID 取得團體課程資訊
    public GroupCourseResponse getGroupCourseById(Integer groupCourseId) {
        GroupCourse course = groupCourseRepository.findById(groupCourseId)
                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
        return GroupCourseResponse.from(course);
    }

    // 新增團體課程 (員工)
    @Transactional
    public GroupCourseResponse createGroupCourse(GroupCourseRequest request) {
        Trainer trainer = null;
        if (request.trainerId() != null) {
            trainer = trainerRepository.findById(request.trainerId())
                    .orElseThrow(() -> new ResourceNotFoundException("指定的教練不存在"));
        } else {
            throw new BusinessRuleException("必須指定指導教練");
        }

        GroupCourse course = new GroupCourse();
        course.setTrainer(trainer);
        applyRequest(course, request);

        if (request.courseCategoryId() != null) {
            CourseCategory cat = categoryRepository.findById(request.courseCategoryId()).orElse(null);
            course.setCategory(cat);
        }

        GroupCourse saved = groupCourseRepository.save(course);
        log.info("Employee successfully created group course (ID: {}, name: {}) for Trainer (ID: {})",
                saved.getGroupCourseId(), saved.getName(), trainer.getTrainerId());
        return GroupCourseResponse.from(saved);
    }

    // 更新團體課程資訊 (員工)
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

        if (request.courseCategoryId() != null) {
            CourseCategory cat = categoryRepository.findById(request.courseCategoryId()).orElse(null);
            course.setCategory(cat);
        }

        GroupCourse saved = groupCourseRepository.save(course);
        log.info("Employee successfully updated group course (ID: {})", saved.getGroupCourseId());
        return GroupCourseResponse.from(saved);
    }

    // 下架/停用團體課程 (員工)
    @Transactional
    public void deleteGroupCourse(Integer groupCourseId) {
        GroupCourse course = groupCourseRepository.findById(groupCourseId)
                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));

        course.setStatus(CourseStatus.INACTIVE);
        groupCourseRepository.save(course);
        log.info("Employee successfully deactivated group course (ID: {})", groupCourseId);
    }

    private void applyRequest(GroupCourse course, GroupCourseRequest request) {
        course.setName(request.name());
        course.setMaxCapacity(request.maxCapacity() != null ? request.maxCapacity() : 20);
        course.setDescription(request.description());
        course.setImageUrl(request.imageUrl());
        course.setIsTrial(request.isTrial() != null ? request.isTrial() : false);
        course.setStatus(request.status() != null ? request.status() : CourseStatus.ACTIVE);
    }
}
