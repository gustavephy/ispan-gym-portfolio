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
import tw.com.ispan.gym.dto.course.UpdatePrivateCourseRequest;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.repository.course.CourseCategoryRepository;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;

import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;

@Service
@Slf4j
@RequiredArgsConstructor
public class PrivateCourseService {
        private final PrivateCourseRepository privateCourseRepository;
        private final TrainerService trainerService;
        private final CourseCategoryRepository courseCategoryRepository;

        public List<PrivateCourseResponse> getPublicCourses() {
                List<PrivateCourse> courses = privateCourseRepository.findByStatus(CourseStatus.ACTIVE);
                return courses.stream()
                                .map(PrivateCourseResponse::from)
                                .collect(Collectors.toList());
        }

        public PageResponse<PrivateCourseResponse> searchPrivateCoursesPaged(int page, int size, CourseStatus status, Integer categoryId, Integer trainerId, String search) {
                int safePage = Math.max(page, 0);
                int safeSize = Math.min(Math.max(size, 1), 100);

                org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(safePage, safeSize, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
                org.springframework.data.domain.Page<PrivateCourse> result = privateCourseRepository.searchPrivateCourses(status, categoryId, trainerId, search, pageable);

                List<PrivateCourseResponse> items = result.getContent().stream()
                                .map(PrivateCourseResponse::from)
                                .collect(Collectors.toList());

                return new PageResponse<>(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
        }

        // 取得教練名下的課程
        public List<PrivateCourseResponse> getMyCourses(Trainer trainer) {
                List<PrivateCourse> courses = privateCourseRepository.findByTrainerId(trainer.getId());
                return courses.stream().map(PrivateCourseResponse::from).collect(Collectors.toList());
        }

        /**
         * 新增私教課。目前沒有任何 Controller 對外開放此方法（尚未串接 HTTP 端點），
         * 但參數已改成 trainerId 而非直接接收 Trainer 實體——未來串接時務必透過這裡的
         * lockAndAssertAvailableForNewBusiness() 解出鎖定後的 Trainer，不要讓呼叫端自己
         * 先用不鎖定的查詢載入 Trainer 再傳進來，否則會踩到 identity map 陷阱、鎖形同虛設
         * （見 TrainerService 對應方法的說明，以及 CoachSlotService.createSlot() 的用法）。
         */
        @Transactional
        public PrivateCourseResponse createCourse(PrivateCourseRequest request, Integer trainerId) {
                // §9 跨模組防護：UNAVAILABLE／已軟刪除的教練不可新增私教課商品
                // （見 trainer_management_next_phase 規格基準）
                Trainer trainer = trainerService.lockAndAssertAvailableForNewBusiness(trainerId);

                CourseCategory category = null;
                if (request.courseCategoryId() != null) {
                        category = courseCategoryRepository.findById(request.courseCategoryId())
                                        .orElseThrow(() -> new ResourceNotFoundException("查無此課程分類"));
                }

                PrivateCourse course = new PrivateCourse();
                course.setTrainer(trainer);
                course.setCategory(category);
                applyRequest(course, request);
                PrivateCourse saved = privateCourseRepository.save(course);
                log.info("Trainer (ID: {}) successfully created private course (ID: {}, name: {})",
                                trainer.getId(),
                                saved.getId(), saved.getName());
                return PrivateCourseResponse.from(saved);
        }

        /**
         * 更新私教課一般欄位（不含 status／trainerId，見 {@link UpdatePrivateCourseRequest}
         * 的說明——先前用共用的 PrivateCourseRequest 會讓這個方法能繞過
         * activateByAdmin() 的教練可服務狀態檢查直接把 status 改回 ACTIVE，
         * code review 發現，2026-08-19）。
         *
         * <p>trainerId 只用來做擁有者比對，不是「新增業務」，不需要鎖定；改用 Integer
         * 而非 Trainer 實體純粹是避免未來串接 Controller 時，又重演 createCourse() 先前
         * 那種「呼叫端自己先不鎖定查出 Trainer 再傳進來」的模式。
         */
        @Transactional
        public PrivateCourseResponse updatePrivateCourse(Integer privateCourseId, UpdatePrivateCourseRequest request,
                        Integer trainerId) {
                PrivateCourse privateCourse = privateCourseRepository.findById(privateCourseId)
                                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
                if (!privateCourse.getTrainer().getId().equals(trainerId)) {
                        log.warn("Trainer (ID: {}) attempted to update unauthorized private course (ID: {})",
                                        trainerId, privateCourse.getId());
                        throw new ForbiddenAccessException("您無權修改此課程");
                }
                applyUpdateRequest(privateCourse, request);
                if (request.courseCategoryId() != null) {
                        CourseCategory category = courseCategoryRepository.findById(request.courseCategoryId())
                                        .orElseThrow(() -> new ResourceNotFoundException("查無此課程分類"));
                        privateCourse.setCategory(category);
                }
                PrivateCourse saved = privateCourseRepository.save(privateCourse);
                log.info("Trainer (ID: {}) successfully updated private course (ID: {})", trainerId,
                                saved.getId());
                return PrivateCourseResponse.from(saved);
        }

        /** 教練本人下架自己的私教課；擁有者檢查理由同 {@link #updatePrivateCourse} */
        @Transactional
        public void deletePrivateCourse(Integer privateCourseId, Integer trainerId) {
                PrivateCourse privateCourse = privateCourseRepository.findById(privateCourseId)
                                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
                if (!privateCourse.getTrainer().getId().equals(trainerId)) {
                        log.warn("Trainer (ID: {}) attempted to delete unauthorized private course (ID: {})",
                                        trainerId, privateCourse.getId());
                        throw new ForbiddenAccessException("您無權刪除此課程");
                }
                privateCourse.setStatus(CourseStatus.INACTIVE);
                privateCourseRepository.save(privateCourse);
                log.info("Trainer (ID: {}) successfully deleted private course (ID: {})", trainerId,
                                privateCourse.getId());
        }

        /**
         * ADMIN 下架私教課商品，成功冪等（已是 INACTIVE 時直接回傳現有資料，不報錯）。
         * 不做擁有者檢查——ADMIN 可下架任何教練名下的商品，跟 GroupCourseService.deactivateCourse()
         * 是同一套設計。新增這個端點的直接原因：Trainer 停用前會檢查「名下是否還有生效中
         * （ACTIVE）的私教課」，在這之前完全沒有任何 Controller 能把 PrivateCourse 設回
         * INACTIVE，等於停用流程被卡死、沒有解法（code review 發現，2026-08-19）。
         */
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

        /**
         * ADMIN 重新上架私教課商品，成功冪等。實際觸發狀態轉換時才檢查教練可服務狀態
         * （§9 跨模組防護）——UNAVAILABLE／已軟刪除的教練名下不可重新上架商品，
         * 已是 ACTIVE 時視為 no-op，不重新檢查。
         */
        @Transactional
        public PrivateCourseResponse activateByAdmin(Integer privateCourseId) {
                PrivateCourse course = privateCourseRepository.findById(privateCourseId)
                                .orElseThrow(() -> new ResourceNotFoundException("課程不存在"));
                if (course.getStatus() != CourseStatus.ACTIVE) {
                        trainerService.lockAndAssertAvailableForNewBusiness(course.getTrainer().getId());
                        course.setStatus(CourseStatus.ACTIVE);
                        course = privateCourseRepository.save(course);
                }
                return PrivateCourseResponse.from(course);
        }

        /** ADMIN 查詢某教練名下所有私教課商品（不分狀態），供停用前確認要下架哪些商品用 */
        public List<PrivateCourseResponse> getCoursesByTrainerForAdmin(Integer trainerId) {
                return privateCourseRepository.findByTrainerId(trainerId).stream()
                                .map(PrivateCourseResponse::from)
                                .collect(Collectors.toList());
        }

        // 內用方法，只給 createCourse 使用，不對外開放。建立時可以直接設定初始 status，
        // 跟「一般更新不可改 status」是不同情境——建立當下還沒有「繞過重新上架檢查」的問題，
        // 因為根本還沒有舊資料可以繞過。courseCategoryId 刻意不在這裡處理——它需要查
        // CourseCategoryRepository 並處理查無此分類的情況，不是單純的 setter，故留給
        // createCourse() 自己在呼叫這個方法前後處理（比照 GroupCourseService 的既有風格；
        // 先前這裡完全沒做，courseCategoryId 傳了會被靜默忽略，code review 發現，2026-08-19）。
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
