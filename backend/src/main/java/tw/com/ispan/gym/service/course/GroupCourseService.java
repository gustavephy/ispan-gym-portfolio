package tw.com.ispan.gym.service.course;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.dto.course.GroupCourseRequest;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.repository.course.CourseCategoryRepository;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;

@RequiredArgsConstructor
@Service
@Slf4j
public class GroupCourseService {
    private final GroupCourseRepository groupCourseRepository;
    private final CourseCategoryRepository courseCategoryRepository;

    // 取得前台公開的啟用中團體課程清單
    public List<GroupCourseResponse> getPublicCourses() {
        log.info("request to get public courses");
        List<GroupCourse> courses = groupCourseRepository.findByStatus(CourseStatus.ACTIVE);
        return courses.stream().map(GroupCourseResponse::from).collect(Collectors.toList());
    }

    // 取得目前教練名下的團體課程清單
    public List<GroupCourseResponse> getMyCourse(Trainer trainer) {
        log.info("request to get my courses");
        List<GroupCourse> courses = groupCourseRepository.findByTrainerId(trainer.getId());
        return courses.stream().map(GroupCourseResponse::from).collect(Collectors.toList());
    }

    // 新增團課
    @Transactional
    public GroupCourseResponse createGroupCourse(GroupCourseRequest request, Trainer trainer) {
        log.info("request to create group course: {}", request.name());
        GroupCourse groupCourse = new GroupCourse();
        groupCourse.setTrainer(trainer);
        applyRequest(groupCourse, request);

        GroupCourse saved = groupCourseRepository.save(groupCourse);
        return GroupCourseResponse.from(saved);
    }

    // 更新團課
    @Transactional
    public GroupCourseResponse updateGroupCourse(Integer groupCourseId, GroupCourseRequest request, Trainer trainer) {
        log.info("request to update group course: {}", groupCourseId);
        GroupCourse groupCourse = groupCourseRepository.findById(groupCourseId)
                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
        if (!groupCourse.getTrainer().getId().equals(trainer.getId())) {
            throw new ForbiddenAccessException("您無權修改此課程");
        }
        applyRequest(groupCourse, request);
        GroupCourse saved = groupCourseRepository.save(groupCourse);
        return GroupCourseResponse.from(saved);
    }

    // 下架團課
    @Transactional
    public void deleteGroupCourse(Integer groupCourseId, Trainer trainer) {
        log.info("request to delete group course: {}", groupCourseId);
        GroupCourse groupCourse = groupCourseRepository.findById(groupCourseId)
                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
        if (!groupCourse.getTrainer().getId().equals(trainer.getId())) {
            throw new ForbiddenAccessException("您無權下架此課程");
        }
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
