package tw.com.ispan.gym.controller.trainer;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.coach.AdminCoachSlotResponse;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.dto.trainer.AdminTrainerResponse;
import tw.com.ispan.gym.dto.trainer.AdminTrainerStatusUpdateRequest;
import tw.com.ispan.gym.dto.trainer.AdminTrainerSummaryResponse;
import tw.com.ispan.gym.dto.trainer.AdminTrainerUpdateRequest;
import tw.com.ispan.gym.dto.trainer.TrainerSuspensionCheckResponse;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.service.coach.CoachSlotService;
import tw.com.ispan.gym.service.trainer.AdminTrainerService;

/**
 * ADMIN 教練管理：列表／單筆查詢／代改專業資料／可服務狀態切換／停用預檢／停用／恢復。
 * 建立教練仍在既有的 TrainerController（POST /api/employees/{employeeId}/trainer），
 * 刻意不搬進 /api/admin/trainers 底下——那個路徑已上線且有測試覆蓋，搬家的一致性收益
 * 不值得冒風險去動 SecurityConfig 與既有測試（見 trainer_management_next_phase 規格基準）。
 *
 * <p>/api/admin/** 已由正式 SecurityConfig 的 URL matcher 使用 hasRole("ADMIN") 保護，
 * 這裡的方法層級 @PreAuthorize 是第二層防護，比照 AdminEmployeeController 既有風格。
 */
@RestController
@RequestMapping("/api/admin/trainers")
public class AdminTrainerController {

    private final AdminTrainerService adminTrainerService;
    private final CoachSlotService coachSlotService;

    public AdminTrainerController(AdminTrainerService adminTrainerService, CoachSlotService coachSlotService) {
        this.adminTrainerService = adminTrainerService;
        this.coachSlotService = coachSlotService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Page<AdminTrainerSummaryResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) TrainerStatus status,
            @RequestParam(defaultValue = "false") boolean deleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return ResponseEntity.ok(adminTrainerService.list(keyword, status, deleted, page, size, sort, direction));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{trainerId}")
    public AdminTrainerResponse getById(@PathVariable Integer trainerId) {
        return adminTrainerService.getById(trainerId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{trainerId}")
    public AdminTrainerResponse update(@PathVariable Integer trainerId,
            @Valid @RequestBody AdminTrainerUpdateRequest request) {
        return adminTrainerService.update(trainerId, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{trainerId}/status")
    public AdminTrainerResponse changeStatus(@PathVariable Integer trainerId,
            @Valid @RequestBody AdminTrainerStatusUpdateRequest request) {
        return adminTrainerService.changeStatus(trainerId, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{trainerId}/suspension-check")
    public TrainerSuspensionCheckResponse suspensionCheck(@PathVariable Integer trainerId) {
        return adminTrainerService.suspensionCheck(trainerId);
    }

    /**
     * 查詢指定教練的排課情況（唯讀），供管理端「教練排課查詢」頁面使用。
     * status／dateFrom／dateTo 篩選語意與教練自助端 {@code /api/trainer/schedules} 相同，
     * 見 {@link CoachSlotService#getSlotsForAdmin} 的說明。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{trainerId}/coach-slots")
    public ResponseEntity<PageResponse<AdminCoachSlotResponse>> getCoachSlots(
            @PathVariable Integer trainerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return ResponseEntity.ok(coachSlotService.getSlotsForAdmin(trainerId, page, size, status, dateFrom, dateTo));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{trainerId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@PathVariable Integer trainerId) {
        adminTrainerService.suspend(trainerId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{trainerId}/restore")
    public AdminTrainerResponse restore(@PathVariable Integer trainerId) {
        return adminTrainerService.restore(trainerId);
    }
}
