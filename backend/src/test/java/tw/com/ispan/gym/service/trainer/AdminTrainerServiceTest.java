package tw.com.ispan.gym.service.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import tw.com.ispan.gym.dto.trainer.AdminTrainerResponse;
import tw.com.ispan.gym.dto.trainer.AdminTrainerStatusUpdateRequest;
import tw.com.ispan.gym.dto.trainer.AdminTrainerSummaryResponse;
import tw.com.ispan.gym.dto.trainer.AdminTrainerUpdateRequest;
import tw.com.ispan.gym.dto.trainer.TrainerSuspensionCheckResponse;
import tw.com.ispan.gym.entity.account.Account;
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
 * 純 Mockito 單元測試（比照 AdminEmployeeServiceTest），不連資料庫。
 * 停用前的四項阻塞檢查（排班／預約／私教課／團課場次）各自對應一支 Repository，
 * 全部 mock，只驗證「count > 0 時擋下、全部為 0 時放行」，不重測各 Repository 自己的查詢邏輯。
 */
class AdminTrainerServiceTest {

    private static final Integer TRAINER_ID = 7;
    private static final Integer EMPLOYEE_ID = 22;

    private final TrainerRepository trainers = mock(TrainerRepository.class);
    private final CoachSlotRepository coachSlots = mock(CoachSlotRepository.class);
    private final CoachBookingRepository coachBookings = mock(CoachBookingRepository.class);
    private final PrivateCourseRepository privateCourses = mock(PrivateCourseRepository.class);
    private final GroupClassSessionRepository groupClassSessions = mock(GroupClassSessionRepository.class);
    private final AdminTrainerService service = new AdminTrainerService(trainers, coachSlots, coachBookings,
            privateCourses, groupClassSessions);

    private Employee employeeOf(EmployeeStatus status) {
        Account account = new Account();
        account.setId(2);
        account.setEmail("coach@example.com");

        Employee employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        employee.setAccount(account);
        employee.setFirstName("小華");
        employee.setLastName("陳");
        employee.setStatus(status);
        return employee;
    }

    private Trainer trainerOf(Employee employee, TrainerStatus status) {
        Trainer trainer = new Trainer();
        trainer.setTrainerId(TRAINER_ID);
        trainer.setEmployee(employee);
        trainer.setSpecialty("重量訓練");
        trainer.setExperience("五年健身房授課經驗");
        trainer.setBio("專注於初學者的肌力養成。");
        trainer.setStatus(status);
        return trainer;
    }

    /** 全部無阻塞：四支 Repository 都回 0，供只想測試「放行」路徑的案例重用 */
    private void stubNoBlockers() {
        when(coachSlots.countOpenByTrainerId(eq(TRAINER_ID), any())).thenReturn(0L);
        when(coachBookings.countByTrainerIdAndStatus(TRAINER_ID, CoachBookingStatus.PENDING)).thenReturn(0L);
        when(privateCourses.countByTrainerTrainerIdAndStatus(TRAINER_ID, CourseStatus.ACTIVE)).thenReturn(0L);
        when(groupClassSessions.countByTrainerIdAndStatusAndEndAtAfter(eq(TRAINER_ID),
                eq(GroupClassSessionStatus.SCHEDULED), any())).thenReturn(0L);
    }

    // === list() ==================================================================

    @Test
    void list_page小於0時丟InvalidRequest_且不呼叫Repository() {
        assertThatThrownBy(() -> service.list(null, null, false, -1, 10, null, null))
                .isInstanceOf(InvalidRequestException.class);
        verify(trainers, never()).findTrainersForAdmin(any(), any(), anyBoolean(), any());
    }

    @Test
    void list_size為0或超過上限時丟InvalidRequest() {
        assertThatThrownBy(() -> service.list(null, null, false, 0, 0, null, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, false, 0, 101, null, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void list_空白keyword正規化為null() {
        when(trainers.findTrainersForAdmin(isNull(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(java.util.List.of()));

        service.list("   ", null, false, 0, 10, null, null);

        verify(trainers).findTrainersForAdmin(isNull(), eq(null), eq(false), any());
    }

    @Test
    void list_正確映射為摘要DTO() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        Page<Trainer> page = new PageImpl<>(java.util.List.of(trainer), PageRequest.of(0, 10), 1);
        when(trainers.findTrainersForAdmin(any(), any(), anyBoolean(), any())).thenReturn(page);

        Page<AdminTrainerSummaryResponse> result = service.list(null, null, false, 0, 10, null, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).trainerId()).isEqualTo(TRAINER_ID);
        assertThat(result.getContent().get(0).email()).isEqualTo("coach@example.com");
    }

    @Test
    void list_deleted參數原樣傳給Repository() {
        when(trainers.findTrainersForAdmin(any(), any(), eq(true), any()))
                .thenReturn(new PageImpl<>(java.util.List.of()));

        service.list(null, null, true, 0, 10, null, null);

        verify(trainers).findTrainersForAdmin(any(), any(), eq(true), any());
    }

    @Test
    void list_sort為非法值時回退trainerId() {
        when(trainers.findTrainersForAdmin(any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(java.util.List.of()));

        service.list(null, null, false, 0, 10, "not-a-real-field", null);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(trainers).findTrainersForAdmin(any(), any(), anyBoolean(), captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("id");
    }

    // === getById() ================================================================

    @Test
    void getById_可查已軟刪除的教練() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findById(TRAINER_ID)).thenReturn(Optional.of(trainer));

        AdminTrainerResponse response = service.getById(TRAINER_ID);

        assertThat(response.deletedAt()).isNotNull();
    }

    @Test
    void getById_不存在時丟ResourceNotFound() {
        when(trainers.findById(TRAINER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(TRAINER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === update() =================================================================

    @Test
    void update_成功寫入專業資料並去除前後空白() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        AdminTrainerUpdateRequest request = new AdminTrainerUpdateRequest();
        request.setSpecialty("  重量訓練、運動恢復  ");
        request.setExperience("健身教練六年");
        request.setBio("專注於肌力訓練");
        request.setProfileImageUrl("https://example.com/trainer/22.jpg");

        AdminTrainerResponse response = service.update(TRAINER_ID, request);

        assertThat(response.specialty()).isEqualTo("重量訓練、運動恢復");
        assertThat(response.profileImageUrl()).isEqualTo("https://example.com/trainer/22.jpg");
    }

    @Test
    void update_找不到或已軟刪除時丟ResourceNotFound() {
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.empty());
        AdminTrainerUpdateRequest request = new AdminTrainerUpdateRequest();
        request.setSpecialty("x");
        request.setExperience("y");
        request.setBio("z");

        assertThatThrownBy(() -> service.update(TRAINER_ID, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === changeStatus() ============================================================

    @Test
    void changeStatus_ACTIVE轉UNAVAILABLE成功() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        AdminTrainerStatusUpdateRequest request = new AdminTrainerStatusUpdateRequest();
        request.setStatus(TrainerStatus.UNAVAILABLE);

        AdminTrainerResponse response = service.changeStatus(TRAINER_ID, request);

        assertThat(response.status()).isEqualTo(TrainerStatus.UNAVAILABLE);
    }

    @Test
    void changeStatus_UNAVAILABLE轉ACTIVE成功() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.UNAVAILABLE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        AdminTrainerStatusUpdateRequest request = new AdminTrainerStatusUpdateRequest();
        request.setStatus(TrainerStatus.ACTIVE);

        AdminTrainerResponse response = service.changeStatus(TRAINER_ID, request);

        assertThat(response.status()).isEqualTo(TrainerStatus.ACTIVE);
    }

    @Test
    void changeStatus_相同狀態時丟BusinessRule() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        AdminTrainerStatusUpdateRequest request = new AdminTrainerStatusUpdateRequest();
        request.setStatus(TrainerStatus.ACTIVE);

        assertThatThrownBy(() -> service.changeStatus(TRAINER_ID, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已經是指定狀態");
    }

    @Test
    void changeStatus_Employee非ACTIVE時不可設為ACTIVE() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.INACTIVE), TrainerStatus.UNAVAILABLE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        AdminTrainerStatusUpdateRequest request = new AdminTrainerStatusUpdateRequest();
        request.setStatus(TrainerStatus.ACTIVE);

        assertThatThrownBy(() -> service.changeStatus(TRAINER_ID, request))
                .isInstanceOf(BusinessRuleException.class);
    }

    /**
     * code review 發現（2026-08-19）：status 與 deletedAt 是獨立欄位，先前只檢查
     * status != ACTIVE，漏了 Employee 雖然 status 仍是 ACTIVE、但已軟刪除的情況——
     * 條件應跟 restore() 一致，兩者都要檢查。
     */
    @Test
    void changeStatus_Employee狀態ACTIVE但已軟刪除時不可設為ACTIVE() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        employee.setDeletedAt(LocalDateTime.now());
        Trainer trainer = trainerOf(employee, TrainerStatus.UNAVAILABLE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        AdminTrainerStatusUpdateRequest request = new AdminTrainerStatusUpdateRequest();
        request.setStatus(TrainerStatus.ACTIVE);

        assertThatThrownBy(() -> service.changeStatus(TRAINER_ID, request))
                .isInstanceOf(BusinessRuleException.class);
    }

    /** Employee 非 ACTIVE 只擋「轉為 ACTIVE」，轉為 UNAVAILABLE 不受影響（規格明訂） */
    @Test
    void changeStatus_Employee非ACTIVE時仍可設為UNAVAILABLE() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.INACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        AdminTrainerStatusUpdateRequest request = new AdminTrainerStatusUpdateRequest();
        request.setStatus(TrainerStatus.UNAVAILABLE);

        AdminTrainerResponse response = service.changeStatus(TRAINER_ID, request);

        assertThat(response.status()).isEqualTo(TrainerStatus.UNAVAILABLE);
    }

    @Test
    void changeStatus_已軟刪除時丟ResourceNotFound() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        AdminTrainerStatusUpdateRequest request = new AdminTrainerStatusUpdateRequest();
        request.setStatus(TrainerStatus.UNAVAILABLE);

        assertThatThrownBy(() -> service.changeStatus(TRAINER_ID, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === suspensionCheck() =========================================================

    @Test
    void suspensionCheck_無阻塞時canSuspend為true() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findById(TRAINER_ID)).thenReturn(Optional.of(trainer));
        stubNoBlockers();

        TrainerSuspensionCheckResponse response = service.suspensionCheck(TRAINER_ID);

        assertThat(response.canSuspend()).isTrue();
        assertThat(response.blockers().openSlots()).isZero();
    }

    @Test
    void suspensionCheck_有任一阻塞時canSuspend為false() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findById(TRAINER_ID)).thenReturn(Optional.of(trainer));
        stubNoBlockers();
        when(coachBookings.countByTrainerIdAndStatus(TRAINER_ID, CoachBookingStatus.PENDING)).thenReturn(1L);

        TrainerSuspensionCheckResponse response = service.suspensionCheck(TRAINER_ID);

        assertThat(response.canSuspend()).isFalse();
        assertThat(response.blockers().activeBookings()).isEqualTo(1L);
    }

    @Test
    void suspensionCheck_已停用時丟BusinessRule() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findById(TRAINER_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.suspensionCheck(TRAINER_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    // === suspend() ==================================================================

    @Test
    void suspend_無阻塞時成功寫入deletedAt() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        stubNoBlockers();

        service.suspend(TRAINER_ID);

        assertThat(trainer.getDeletedAt()).isNotNull();
    }

    @Test
    void suspend_有開放排班時擋下且不寫入deletedAt() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        stubNoBlockers();
        when(coachSlots.countOpenByTrainerId(eq(TRAINER_ID), any())).thenReturn(2L);

        assertThatThrownBy(() -> service.suspend(TRAINER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("未完成業務");
        assertThat(trainer.getDeletedAt()).isNull();
    }

    @Test
    void suspend_有未完成私教課時擋下() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        stubNoBlockers();
        when(privateCourses.countByTrainerTrainerIdAndStatus(TRAINER_ID, CourseStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.suspend(TRAINER_ID))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(trainer.getDeletedAt()).isNull();
    }

    @Test
    void suspend_有未完成團課場次時擋下() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        stubNoBlockers();
        when(groupClassSessions.countByTrainerIdAndStatusAndEndAtAfter(eq(TRAINER_ID),
                eq(GroupClassSessionStatus.SCHEDULED), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.suspend(TRAINER_ID))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(trainer.getDeletedAt()).isNull();
    }

    @Test
    void suspend_已停用時丟ResourceNotFound() {
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.suspend(TRAINER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suspend_不刪除任何歷史資料只設定deletedAt() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));
        stubNoBlockers();

        service.suspend(TRAINER_ID);

        assertThat(trainer.getSpecialty()).isEqualTo("重量訓練");
        verify(trainers, never()).delete(any());
        verify(trainers, never()).deleteById(any());
    }

    // === restore() ===================================================================

    @Test
    void restore_成功後deletedAt清空且狀態固定為UNAVAILABLE() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));

        AdminTrainerResponse response = service.restore(TRAINER_ID);

        assertThat(response.deletedAt()).isNull();
        assertThat(response.status()).isEqualTo(TrainerStatus.UNAVAILABLE);
        assertThat(response.trainerId()).isEqualTo(TRAINER_ID);
    }

    @Test
    void restore_恢復後同步Employee最新姓名() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        employee.setFirstName("小明");
        employee.setLastName("林");
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        trainer.setFirstName("舊名字");
        trainer.setLastName("舊姓氏");
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));

        service.restore(TRAINER_ID);

        assertThat(trainer.getFirstName()).isEqualTo("小明");
        assertThat(trainer.getLastName()).isEqualTo("林");
    }

    @Test
    void restore_未停用時丟BusinessRule() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.restore(TRAINER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("並未停用");
    }

    @Test
    void restore_Employee已軟刪除時丟BusinessRule() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        employee.setDeletedAt(LocalDateTime.now());
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.restore(TRAINER_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void restore_Employee非ACTIVE時丟BusinessRule() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.INACTIVE), TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.restore(TRAINER_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void restore_不存在時丟ResourceNotFound() {
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(TRAINER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void restore_不建立新資料只重用原trainerId() {
        Trainer trainer = trainerOf(employeeOf(EmployeeStatus.ACTIVE), TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));

        service.restore(TRAINER_ID);

        verify(trainers, never()).save(any());
        assertThat(trainer.getTrainerId()).isEqualTo(TRAINER_ID);
    }
}
