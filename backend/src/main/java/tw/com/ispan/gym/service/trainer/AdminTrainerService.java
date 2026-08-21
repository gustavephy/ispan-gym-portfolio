package tw.com.ispan.gym.service.trainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.trainer.AdminTrainerResponse;
import tw.com.ispan.gym.dto.trainer.AdminTrainerStatusUpdateRequest;
import tw.com.ispan.gym.dto.trainer.AdminTrainerSummaryResponse;
import tw.com.ispan.gym.dto.trainer.AdminTrainerUpdateRequest;
import tw.com.ispan.gym.dto.trainer.TrainerSuspensionCheckResponse;
import tw.com.ispan.gym.dto.trainer.TrainerSuspensionCheckResponse.Blockers;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.InvalidRequestException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coach.CoachBookingRepository;
import tw.com.ispan.gym.repository.coach.CoachSlotRepository;
import tw.com.ispan.gym.repository.coach.GroupClassSessionRepository;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * ADMIN 教練管理：列表／單筆查詢／代改專業資料／可服務狀態切換／停用（軟刪除）／恢復。
 * 不動既有的 Trainer 建立（TrainerService.create()，員工升級為教練）與公開查詢。
 *
 * <p>停用前必須在同一交易內重新檢查未完成業務（排班／預約／私教課／團課場次），
 * 不可信任 suspensionCheck() 預檢的結果——預檢與正式停用之間可能有新業務產生。
 * 恢復固定回到 UNAVAILABLE，即使停用前是 ACTIVE，也不直接跳回 ACTIVE——
 * ADMIN 需重新確認資料後才明確切回 ACTIVE（見 trainer_management_next_phase 規格基準）。
 */
@Service
@Transactional(readOnly = true)
public class AdminTrainerService {

    private static final Set<String> ALLOWED_SORTS = Set.of("trainerId", "name", "status", "createdAt", "updatedAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final TrainerRepository trainers;
    private final CoachSlotRepository coachSlots;
    private final CoachBookingRepository coachBookings;
    private final PrivateCourseRepository privateCourses;
    private final GroupClassSessionRepository groupClassSessions;

    public AdminTrainerService(TrainerRepository trainers, CoachSlotRepository coachSlots,
            CoachBookingRepository coachBookings, PrivateCourseRepository privateCourses,
            GroupClassSessionRepository groupClassSessions) {
        this.trainers = trainers;
        this.coachSlots = coachSlots;
        this.coachBookings = coachBookings;
        this.privateCourses = privateCourses;
        this.groupClassSessions = groupClassSessions;
    }

    public Page<AdminTrainerSummaryResponse> list(String keyword, TrainerStatus status, boolean deleted,
            int page, int size, String sort, String direction) {
        String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Pageable pageable = buildPageable(page, size, sort, direction);
        return trainers.findTrainersForAdmin(normalizedKeyword, status, deleted, pageable)
                .map(AdminTrainerSummaryResponse::from);
    }

    /** ADMIN 可查已軟刪除的 Trainer，不像公開查詢那樣過濾 deletedAt */
    public AdminTrainerResponse getById(Integer trainerId) {
        Trainer trainer = trainers.findById(trainerId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此教練"));
        return AdminTrainerResponse.from(trainer);
    }

    @Transactional
    public AdminTrainerResponse update(Integer trainerId, AdminTrainerUpdateRequest request) {
        Trainer trainer = lockOrThrow(trainerId);

        trainer.setSpecialty(request.getSpecialty().trim());
        trainer.setExperience(request.getExperience().trim());
        trainer.setBio(request.getBio().trim());
        trainer.setProfileImageUrl(normalizeProfileImageUrl(request.getProfileImageUrl()));

        return AdminTrainerResponse.from(trainer);
    }

    @Transactional
    public AdminTrainerResponse changeStatus(Integer trainerId, AdminTrainerStatusUpdateRequest request) {
        Trainer trainer = lockOrThrow(trainerId);

        if (trainer.getStatus() == request.getStatus()) {
            throw new BusinessRuleException("此教練已經是指定狀態");
        }
        // 條件跟 restore() 一致：ACTIVE 不只看 status，deletedAt 是獨立欄位，
        // 兩者都要檢查（code review 發現，2026-08-19，先前漏了 deletedAt）
        if (request.getStatus() == TrainerStatus.ACTIVE
                && (trainer.getEmployee().getDeletedAt() != null
                        || trainer.getEmployee().getStatus() != EmployeeStatus.ACTIVE)) {
            throw new BusinessRuleException("此教練所屬員工目前不是在職狀態，無法設為可服務");
        }

        trainer.setStatus(request.getStatus());
        return AdminTrainerResponse.from(trainer);
    }

    public TrainerSuspensionCheckResponse suspensionCheck(Integer trainerId) {
        Trainer trainer = trainers.findById(trainerId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此教練"));
        if (trainer.getDeletedAt() != null) {
            throw new BusinessRuleException("此教練已停用");
        }
        return TrainerSuspensionCheckResponse.from(countBlockers(trainerId));
    }

    @Transactional
    public void suspend(Integer trainerId) {
        Trainer trainer = lockOrThrow(trainerId);

        Blockers blockers = countBlockers(trainerId);
        if (blockers.any()) {
            throw new BusinessRuleException("教練仍有未完成業務，無法停用（排班 %d 筆、預約 %d 筆、私教課 %d 筆、團課場次 %d 筆）"
                    .formatted(blockers.openSlots(), blockers.activeBookings(), blockers.activePrivateCourses(),
                            blockers.upcomingGroupClassSessions()));
        }

        trainer.setDeletedAt(LocalDateTime.now());
    }

    @Transactional
    public AdminTrainerResponse restore(Integer trainerId) {
        Trainer trainer = trainers.findByIdForUpdate(trainerId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此教練"));
        if (trainer.getDeletedAt() == null) {
            throw new BusinessRuleException("此教練目前並未停用，無需恢復");
        }

        Employee employee = trainer.getEmployee();
        if (employee.getDeletedAt() != null || employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessRuleException("此教練所屬員工目前不是在職狀態，無法恢復");
        }

        trainer.setDeletedAt(null);
        trainer.setStatus(TrainerStatus.UNAVAILABLE);
        // 停用期間 Employee 姓名可能已變動，SchemaFixupRunner 的整批同步只在下次重啟才跑，
        // 這裡即時補一次，理由同 AdminEmployeeService.update() 同步 Trainer 姓名快照的說明
        trainer.setFirstName(employee.getFirstName());
        trainer.setLastName(employee.getLastName());

        return AdminTrainerResponse.from(trainer);
    }

    private Blockers countBlockers(Integer trainerId) {
        LocalDateTime now = LocalDateTime.now();
        long openSlots = coachSlots.countOpenByTrainerId(trainerId, now);
        long activeBookings = coachBookings.countByTrainerIdAndStatus(trainerId, CoachBookingStatus.PENDING);
        long activePrivateCourses = privateCourses.countByTrainerIdAndStatus(trainerId, CourseStatus.ACTIVE);
        long upcomingGroupClassSessions = groupClassSessions.countByTrainerIdAndStatusAndEndAtAfter(
                trainerId, GroupClassSessionStatus.SCHEDULED, now);
        return new Blockers(openSlots, activeBookings, activePrivateCourses, upcomingGroupClassSessions);
    }

    private Trainer lockOrThrow(Integer trainerId) {
        return trainers.findByIdForUpdate(trainerId)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此教練"));
    }

    private static String normalizeProfileImageUrl(String profileImageUrl) {
        return (profileImageUrl == null || profileImageUrl.isBlank()) ? null : profileImageUrl.trim();
    }

    private Pageable buildPageable(int page, int size, String sort, String direction) {
        if (page < 0) {
            throw new InvalidRequestException("頁碼不可小於 0");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("每頁筆數必須介於 1 到 " + MAX_PAGE_SIZE);
        }

        String normalizedSort = sort == null ? "trainerId" : sort.trim();
        String effectiveSort = ALLOWED_SORTS.contains(normalizedSort) ? normalizedSort : "trainerId";

        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        List<Sort.Order> orders = new ArrayList<>();
        switch (effectiveSort) {
            case "name" -> {
                orders.add(new Sort.Order(dir, "employee.lastName"));
                orders.add(new Sort.Order(dir, "employee.firstName"));
                orders.add(new Sort.Order(Sort.Direction.ASC, "id"));
            }
            case "status" -> {
                orders.add(new Sort.Order(dir, "status"));
                orders.add(new Sort.Order(Sort.Direction.ASC, "id"));
            }
            case "createdAt" -> {
                orders.add(new Sort.Order(dir, "createdAt"));
                orders.add(new Sort.Order(Sort.Direction.ASC, "id"));
            }
            case "updatedAt" -> {
                orders.add(new Sort.Order(dir, "updatedAt"));
                orders.add(new Sort.Order(Sort.Direction.ASC, "id"));
            }
            default -> orders.add(new Sort.Order(dir, "id"));
        }
        return PageRequest.of(page, size, Sort.by(orders));
    }
}
