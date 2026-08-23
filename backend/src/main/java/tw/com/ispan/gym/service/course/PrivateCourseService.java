package tw.com.ispan.gym.service.course;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.PrivateCourseRequest;
import tw.com.ispan.gym.dto.course.PrivateCourseResponse;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class PrivateCourseService {
        private final PrivateCourseRepository privateCourseRepository;

        // 取得前台商城販售中的啟用中私人課程方案列表
        public List<PrivateCourseResponse> getPublicCourses() {
                List<PrivateCourse> courses = privateCourseRepository.findByStatus(CourseStatus.ACTIVE);
                return courses.stream().map(PrivateCourseResponse::from).collect(Collectors.toList());
        }

        // 取得前台商城展示中的啟用中私人課程方案列表 (不分頁)
        public List<PrivateCourseResponse> getPublicCourses(
                        String keyword, Integer trainerId, Integer categoryId) {
                return getAllCourses(keyword, trainerId, categoryId, CourseStatus.ACTIVE, null, null, 0, 9999)
                                .getContent();
        }

        // 取得私人課程清單（分頁，支援關鍵字、教練ID與日期過濾）
        public Page<PrivateCourseResponse> getAllCourses(
                        String keyword,
                        Integer trainerId,
                        Integer categoryId,
                        CourseStatus status,
                        LocalDate startDate,
                        LocalDate endDate,
                        int page,
                        int pageSize) {
                PageRequest pageRequest = PageRequest.of(page, pageSize, Sort.by("privateCourseId").descending());

                LocalDateTime startLdt = null;
                if (startDate != null) {
                        startLdt = startDate.atStartOfDay();
                }

                LocalDateTime endLdt = null;
                if (endDate != null) {
                        endLdt = endDate.atTime(23, 59, 59, 999999);
                }

                Page<PrivateCourse> courses = privateCourseRepository.findCoursesWithFilters(
                                status,
                                trainerId,
                                categoryId,
                                (keyword != null && !keyword.isBlank()) ? keyword.trim() : null,
                                startLdt,
                                endLdt,
                                pageRequest);
                return courses.map(PrivateCourseResponse::from);
        }

        // 根據 ID 取得啟用中的單一私人課程詳情 (前台商城呈現用)
        public PrivateCourseResponse getPublicCourseById(Integer privateCourseId) {
                PrivateCourse course = privateCourseRepository.findById(privateCourseId)
                                .orElseThrow(() -> new ResourceNotFoundException("該私人課程方案不存在"));
                if (course.getStatus() != CourseStatus.ACTIVE) {
                        throw new BusinessRuleException("該私人課程方案已下架");
                }
                return PrivateCourseResponse.from(course);
        }

        // 取得目前教練名下的私人課程清單
        public List<PrivateCourseResponse> getMyCourses(Trainer trainer) {
                List<PrivateCourse> courses = privateCourseRepository.findByTrainerId(trainer.getTrainerId());
                return courses.stream().map(PrivateCourseResponse::from).collect(Collectors.toList());
        }

        // 新增私人課程
        @Transactional
        public PrivateCourseResponse createCourse(PrivateCourseRequest req, Trainer trainer) {
                PrivateCourse course = new PrivateCourse();
                course.setTrainer(trainer);
                applyRequest(course, req);

                PrivateCourse saved = privateCourseRepository.save(course);
                log.info("Trainer (ID: {}) successfully created private course (ID: {}, name: {})",
                                trainer.getTrainerId(),
                                saved.getPrivateCourseId(), saved.getName());
                return PrivateCourseResponse.from(saved);
        }

        // 更新私人課程資訊
        @Transactional
        public PrivateCourseResponse updateCourse(Integer id, PrivateCourseRequest req, Trainer trainer) {
                PrivateCourse course = privateCourseRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));

                if (!course.getTrainer().getTrainerId().equals(trainer.getTrainerId())) {
                        log.warn("Trainer (ID: {}) attempted to update unauthorized private course (ID: {})",
                                        trainer.getTrainerId(), id);
                        throw new ForbiddenAccessException("您無權修改此課程");
                }

                applyRequest(course, req);

                PrivateCourse saved = privateCourseRepository.save(course);
                log.info("Trainer (ID: {}) successfully updated private course (ID: {})", trainer.getTrainerId(),
                                saved.getPrivateCourseId());
                return PrivateCourseResponse.from(saved);
        }

        // 下架/停用私人課程
        @Transactional
        public void deleteCourse(Integer id, Trainer trainer) {
                PrivateCourse course = privateCourseRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));

                if (!course.getTrainer().getTrainerId().equals(trainer.getTrainerId())) {
                        log.warn("Trainer (ID: {}) attempted to deactivate unauthorized private course (ID: {})",
                                        trainer.getTrainerId(), id);
                        throw new ForbiddenAccessException("您無權下架此課程");
                }

                course.setStatus(CourseStatus.INACTIVE);
                privateCourseRepository.save(course);
                log.info("Trainer (ID: {}) successfully deactivated private course (ID: {})", trainer.getTrainerId(),
                                id);
        }

        private void applyRequest(PrivateCourse course, PrivateCourseRequest req) {
                course.setName(req.name());
                course.setMaxCapacity(req.maxCapacity() != null ? req.maxCapacity() : 1);
                course.setSessionPerUnit(req.sessionPerUnit() != null ? req.sessionPerUnit() : 1);
                course.setPrice(req.price() != null ? req.price() : BigDecimal.ZERO);
                course.setDescription(req.description());
                course.setImageUrl(req.imageUrl());
                course.setIsTrial(req.isTrial() != null ? req.isTrial() : false);
                course.setStatus(req.status() != null ? req.status() : CourseStatus.ACTIVE);
        }
}
