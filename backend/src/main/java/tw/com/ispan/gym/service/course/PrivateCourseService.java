package tw.com.ispan.gym.service.course;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.PrivateCourseRequest;
import tw.com.ispan.gym.dto.course.PrivateCourseResponse;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;

import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;

@Service
@Slf4j
@RequiredArgsConstructor
public class PrivateCourseService {
        private final PrivateCourseRepository privateCourseRepository;

        public List<PrivateCourseResponse> getPublicCourses() {
                List<PrivateCourse> courses = privateCourseRepository.findByStatus(CourseStatus.ACTIVE);
                return courses.stream()
                                .map(PrivateCourseResponse::from)
                                .collect(Collectors.toList());
        }

        // 取得教練名下的課程
        public List<PrivateCourseResponse> getMyCourses(Trainer trainer) {
                // 方法名的疊字是必要的：Trainer 主鍵欄位叫 trainerId，衍生查詢路徑須寫全名
                // trainer.trainerId；改成 findByTrainerId 會在啟動時解析失敗
                List<PrivateCourse> courses = privateCourseRepository.findByTrainerTrainerId(trainer.getTrainerId());
                return courses.stream().map(PrivateCourseResponse::from).collect(Collectors.toList());
        }

        // 新增私教課
        @Transactional
        public PrivateCourseResponse createCourse(PrivateCourseRequest request, Trainer trainer) {
                PrivateCourse course = new PrivateCourse();
                course.setTrainer(trainer);
                applyRequest(course, request);
                PrivateCourse saved = privateCourseRepository.save(course);
                log.info("Trainer (ID: {}) successfully created private course (ID: {}, name: {})",
                                trainer.getTrainerId(),
                                saved.getId(), saved.getName());
                return PrivateCourseResponse.from(saved);
        }

        // 更新私教課
        @Transactional
        public PrivateCourseResponse updatePrivateCourse(Integer privateCourseId, PrivateCourseRequest request,
                        Trainer trainer) {
                PrivateCourse privateCourse = privateCourseRepository.findById(privateCourseId)
                                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
                if (!privateCourse.getTrainer().getTrainerId().equals(trainer.getTrainerId())) {
                        log.warn("Trainer (ID: {}) attempted to update unauthorized private course (ID: {})",
                                        trainer.getTrainerId(), privateCourse.getId());
                        throw new ForbiddenAccessException("您無權修改此課程");
                }
                applyRequest(privateCourse, request);
                PrivateCourse saved = privateCourseRepository.save(privateCourse);
                log.info("Trainer (ID: {}) successfully updated private course (ID: {})", trainer.getTrainerId(),
                                saved.getId());
                return PrivateCourseResponse.from(saved);
        }

        // 下架私教課
        @Transactional
        public void deletePrivateCourse(Integer privateCourseId, Trainer trainer) {
                PrivateCourse privateCourse = privateCourseRepository.findById(privateCourseId)
                                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
                if (!privateCourse.getTrainer().getTrainerId().equals(trainer.getTrainerId())) {
                        log.warn("Trainer (ID: {}) attempted to delete unauthorized private course (ID: {})",
                                        trainer.getTrainerId(), privateCourse.getId());
                        throw new ForbiddenAccessException("您無權刪除此課程");
                }
                privateCourse.setStatus(CourseStatus.INACTIVE);
                privateCourseRepository.save(privateCourse);
                log.info("Trainer (ID: {}) successfully deleted private course (ID: {})", trainer.getTrainerId(),
                                privateCourse.getId());
        }

        // 內用方法，提供給createCourse和updatePrivateCourse使用，不對外開放
        private void applyRequest(PrivateCourse course, PrivateCourseRequest request) {
                course.setName(request.name());
                course.setMaxCapacity(request.maxCapacity() != null ? request.maxCapacity() : 1);
                course.setSessionPerUnit(request.sessionPerUnit() != null ? request.sessionPerUnit() : 1);
                course.setPrice(request.price() != null ? request.price() : BigDecimal.ZERO);
                course.setDescription(request.description());
                course.setImageUrl(request.imageUrl());
                course.setIsTrial(request.isTrial() != null ? request.isTrial() : false);
                course.setStatus(request.status() != null ? request.status() : CourseStatus.ACTIVE);
        }
}
