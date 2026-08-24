package tw.com.ispan.gym.service.course;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.PrivateCourseRequest;
import tw.com.ispan.gym.dto.course.PrivateCourseResponse;
import tw.com.ispan.gym.dto.course.UpdatePrivateCourseRequest;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class PrivateCourseService {
        private final PrivateCourseRepository privateCourseRepository;
        private final TrainerService trainerService;

        // 取得前台商城展示中的啟用中私教課列表 (不分頁)
        public List<PrivateCourseResponse> getPublicCourses(
                        String keyword, Integer trainerId, Integer categoryId) {
                return getAllCourses(keyword, trainerId, categoryId, CourseStatus.ACTIVE, null, null);
        }

        // 取得私人課程清單（不分頁，支援關鍵字、教練ID與日期過濾）
        public List<PrivateCourseResponse> getAllCourses(
                        String keyword,
                        Integer trainerId,
                        Integer categoryId,
                        CourseStatus status,
                        LocalDate startDate,
                        LocalDate endDate) {
                LocalDateTime startLdt = null;
                if (startDate != null) {
                        startLdt = startDate.atStartOfDay();
                }

                LocalDateTime endLdt = null;
                if (endDate != null) {
                        endLdt = endDate.atTime(23, 59, 59, 999999);
                }

                List<PrivateCourse> courses = privateCourseRepository.findCoursesWithFilters(
                                status,
                                trainerId,
                                categoryId,
                                (keyword != null && !keyword.isBlank()) ? keyword.trim() : null,
                                startLdt,
                                endLdt);
                return courses.stream()
                                .map(PrivateCourseResponse::from)
                                .collect(Collectors.toList());
        }

        // 根據 ID 取得啟用中的單一私教課詳情 (前台商城呈現用)
        public PrivateCourseResponse getPublicCourseById(Integer privateCourseId) {
                PrivateCourse course = privateCourseRepository.findById(privateCourseId)
                                .orElseThrow(() -> new ResourceNotFoundException("該私人課程方案不存在"));
                if (course.getStatus() != CourseStatus.ACTIVE) {
                        throw new BusinessRuleException("該私人課程方案已下架");
                }
                return PrivateCourseResponse.from(course);
        }

        // 取得目前教練名下的私教課
        public List<PrivateCourseResponse> getMyCourses(Trainer trainer) {
                List<PrivateCourse> courses = privateCourseRepository.findByTrainerId(trainer.getTrainerId());
                return courses.stream().map(PrivateCourseResponse::from).collect(Collectors.toList());
        }

        // 新增私教課（教練）
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

        // 更新私教課 （教練）
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

        // 下架/停用私教課（教練）
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

        // 下架私教課（ADMIN）
        @Transactional
        public PrivateCourseResponse deactivateByAdmin(Integer privateCourseId) {
                PrivateCourse course = privateCourseRepository.findById(privateCourseId)
                                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
                if (course.getStatus() != CourseStatus.INACTIVE) {
                        course.setStatus(CourseStatus.INACTIVE);
                        course = privateCourseRepository.save(course);
                }
                return PrivateCourseResponse.from(course);
        }

        // 重新上架私教課（ADMIN）
        @Transactional
        public PrivateCourseResponse activateByAdmin(Integer privateCourseId) {
                PrivateCourse course = privateCourseRepository.findById(privateCourseId)
                                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
                if (course.getStatus() != CourseStatus.ACTIVE) {
                        trainerService.lockAndAssertAvailableForNewBusiness(course.getTrainer().getTrainerId());
                        course.setStatus(CourseStatus.ACTIVE);
                        course = privateCourseRepository.save(course);
                }
                return PrivateCourseResponse.from(course);
        }

        // ADMIN 查詢某教練名下所有私教課商品（不分狀態），供停用前確認要下架哪些商品用
        public List<PrivateCourseResponse> getCoursesByTrainerForAdmin(Integer trainerId) {
                return privateCourseRepository.findByTrainerId(trainerId).stream()
                                .map(PrivateCourseResponse::from)
                                .collect(Collectors.toList());
        }

        // 內用方法，只給 updatePrivateCourse 使用。刻意不設定 status／trainer——
        // 狀態只能走 deactivateByAdmin()/activateByAdmin()，教練不可透過修改變更。
        // courseCategoryId 同 applyRequest() 的說明，留給 updatePrivateCourse() 自己處理，
        // 且是 null-preserving（沒傳就維持原分類），不是全量覆蓋，因為分類跟這裡其餘
        // 「一定要填」的欄位性質不同。
        private void applyUpdateRequest(PrivateCourse course, UpdatePrivateCourseRequest request) {
                course.setName(request.name());
                course.setMaxCapacity(request.maxCapacity() != null ? request.maxCapacity() : 1);
                course.setSessionPerUnit(request.sessionPerUnit() != null ? request.sessionPerUnit() : 1);
                course.setPrice(request.price() != null ? request.price() : BigDecimal.ZERO);
                course.setDescription(request.description());
                course.setImageUrl(request.imageUrl());
                course.setIsTrial(request.isTrial() != null ? request.isTrial() : false);
        }
}
