package tw.com.ispan.gym.service.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tw.com.ispan.gym.dto.trainer.TrainerCreateRequest;
import tw.com.ispan.gym.dto.trainer.TrainerResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 純 Mockito 單元測試（比照 EmployeeReviewServiceTest），不連資料庫。
 * 併發鎖同樣只驗「有沒有取鎖」，不模擬真正的多執行緒競爭。
 */
class TrainerServiceTest {

    private static final Integer EMPLOYEE_ID = 22;
    private static final Integer TRAINER_ID = 7;

    private final TrainerRepository trainers = mock(TrainerRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final TrainerService service = new TrainerService(trainers, employees);

    private Employee activeEmployee() {
        Account account = new Account();
        account.setId(2);
        account.setEmail("coach@example.com");

        Employee employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        employee.setAccount(account);
        employee.setFirstName("小華");
        employee.setLastName("陳");
        employee.setPhone("0911111111");
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setHireDate(LocalDate.now());
        return employee;
    }

    private TrainerCreateRequest request() {
        TrainerCreateRequest request = new TrainerCreateRequest();
        request.setSpecialty("重量訓練");
        request.setExperience("五年健身房授課經驗");
        request.setBio("專注於初學者的肌力養成。");
        return request;
    }

    private Trainer trainerOf(Employee employee, TrainerStatus status) {
        Trainer trainer = new Trainer();
        trainer.setId(TRAINER_ID);
        trainer.setEmployee(employee);
        trainer.setSpecialty("重量訓練");
        trainer.setExperience("五年健身房授課經驗");
        trainer.setBio("專注於初學者的肌力養成。");
        trainer.setStatus(status);
        return trainer;
    }

    /** save() 模擬資料庫回填主鍵後回傳同一個實體 */
    private void stubSaveAssigningId() {
        when(trainers.save(any(Trainer.class))).thenAnswer(invocation -> {
            Trainer saved = invocation.getArgument(0);
            saved.setId(TRAINER_ID);
            return saved;
        });
    }

    // === create()：成功路徑 ======================================================

    @Test
    void create_成功_回傳trainerId且狀態固定為ACTIVE() {
        Employee employee = activeEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());
        stubSaveAssigningId();

        TrainerResponse response = service.create(EMPLOYEE_ID, request());

        assertThat(response.trainerId()).isEqualTo(TRAINER_ID);
        assertThat(response.status()).isEqualTo(TrainerStatus.ACTIVE);
        assertThat(response.specialty()).isEqualTo("重量訓練");
        assertThat(response.experience()).isEqualTo("五年健身房授課經驗");
        assertThat(response.bio()).isEqualTo("專注於初學者的肌力養成。");
    }

    @Test
    void create_displayName由姓氏加名字組成() {
        Employee employee = activeEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());
        stubSaveAssigningId();

        TrainerResponse response = service.create(EMPLOYEE_ID, request());

        assertThat(response.displayName()).isEqualTo("陳小華");
    }

    @Test
    void create_以悲觀鎖查詢員工避免併發重複建立() {
        Employee employee = activeEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());
        stubSaveAssigningId();

        service.create(EMPLOYEE_ID, request());

        verify(employees).findByIdForUpdate(EMPLOYEE_ID);
        verify(employees, never()).findById(EMPLOYEE_ID);
    }

    @Test
    void create_設定姓名與email快照欄位() {
        Employee employee = activeEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());
        stubSaveAssigningId();

        service.create(EMPLOYEE_ID, request());

        ArgumentCaptor<Trainer> captor = ArgumentCaptor.forClass(Trainer.class);
        verify(trainers).save(captor.capture());
        Trainer saved = captor.getValue();
        // 這三個是建立當下自 Employee／Account 複製的快照欄位，避免留白到下次
        // SchemaFixupRunner 重啟才補（見 Trainer 實體與 TrainerService.create() 的註解）
        assertThat(saved.getFirstName()).isEqualTo("小華");
        assertThat(saved.getLastName()).isEqualTo("陳");
        assertThat(saved.getEmail()).isEqualTo("coach@example.com");
    }

    @Test
    void create_專業資料去除前後空白() {
        Employee employee = activeEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());
        stubSaveAssigningId();
        TrainerCreateRequest request = request();
        request.setSpecialty("  重量訓練  ");
        request.setExperience("  五年健身房授課經驗  ");
        request.setBio("  專注於初學者的肌力養成。  ");

        TrainerResponse response = service.create(EMPLOYEE_ID, request);

        assertThat(response.specialty()).isEqualTo("重量訓練");
        assertThat(response.experience()).isEqualTo("五年健身房授課經驗");
        assertThat(response.bio()).isEqualTo("專注於初學者的肌力養成。");
    }

    @Test
    void create_圖片網址空白字串轉為null() {
        Employee employee = activeEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());
        stubSaveAssigningId();
        TrainerCreateRequest request = request();
        request.setProfileImageUrl("   ");

        TrainerResponse response = service.create(EMPLOYEE_ID, request);

        assertThat(response.profileImageUrl()).isNull();
    }

    @Test
    void create_圖片網址有值時保留() {
        Employee employee = activeEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());
        stubSaveAssigningId();
        TrainerCreateRequest request = request();
        request.setProfileImageUrl("https://example.com/trainer/22.jpg");

        TrainerResponse response = service.create(EMPLOYEE_ID, request);

        assertThat(response.profileImageUrl()).isEqualTo("https://example.com/trainer/22.jpg");
    }

    // === create()：Employee 資格檢查 ==============================================

    @Test
    void create_員工不存在時丟ResourceNotFound() {
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(EMPLOYEE_ID, request()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(trainers, never()).save(any());
    }

    @Test
    void create_員工待審核時丟BusinessRule() {
        Employee employee = activeEmployee();
        employee.setStatus(EmployeeStatus.PENDING_APPROVAL);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.create(EMPLOYEE_ID, request()))
                .isInstanceOf(BusinessRuleException.class);
        verify(trainers, never()).save(any());
    }

    @Test
    void create_員工已停用時丟BusinessRule() {
        Employee employee = activeEmployee();
        employee.setStatus(EmployeeStatus.INACTIVE);
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.create(EMPLOYEE_ID, request()))
                .isInstanceOf(BusinessRuleException.class);
    }

    /** status 與 deletedAt 是兩個獨立欄位，只檢查 status 會漏掉這種情況 */
    @Test
    void create_員工狀態雖為ACTIVE但已軟刪除時仍丟BusinessRule() {
        Employee employee = activeEmployee();
        employee.softDelete();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> service.create(EMPLOYEE_ID, request()))
                .isInstanceOf(BusinessRuleException.class);
        verify(trainers, never()).save(any());
    }

    // === create()：重複建立檢查（revive-not-recreate） =============================

    @Test
    void create_已是教練時丟BusinessRule並提示已經是教練() {
        Employee employee = activeEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID))
                .thenReturn(Optional.of(trainerOf(employee, TrainerStatus.ACTIVE)));

        assertThatThrownBy(() -> service.create(EMPLOYEE_ID, request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已經是教練");
        verify(trainers, never()).save(any());
    }

    /** UNAVAILABLE 只是暫時不可預約，資料列仍然有效，一樣不能重建 */
    @Test
    void create_已有UNAVAILABLE教練資料時仍丟BusinessRule() {
        Employee employee = activeEmployee();
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID))
                .thenReturn(Optional.of(trainerOf(employee, TrainerStatus.UNAVAILABLE)));

        assertThatThrownBy(() -> service.create(EMPLOYEE_ID, request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已經是教練");
        verify(trainers, never()).save(any());
    }

    @Test
    void create_教練資料已軟刪除時丟BusinessRule並提示恢復流程() {
        Employee employee = activeEmployee();
        Trainer deleted = trainerOf(employee, TrainerStatus.ACTIVE);
        deleted.setDeletedAt(LocalDateTime.now());
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.create(EMPLOYEE_ID, request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("恢復流程");
        verify(trainers, never()).save(any());
    }

    // === listPublic() ===========================================================

    @Test
    void listPublic_同時回傳ACTIVE與UNAVAILABLE教練() {
        Employee active = activeEmployee();
        Employee unavailable = activeEmployee();
        unavailable.setId(23);
        unavailable.setFirstName("小明");
        unavailable.setLastName("林");

        Trainer activeTrainer = trainerOf(active, TrainerStatus.ACTIVE);
        Trainer unavailableTrainer = trainerOf(unavailable, TrainerStatus.UNAVAILABLE);
        unavailableTrainer.setId(8);
        when(trainers.findByDeletedAtIsNull()).thenReturn(List.of(activeTrainer, unavailableTrainer));

        List<TrainerResponse> result = service.listPublic();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TrainerResponse::status)
                .containsExactly(TrainerStatus.ACTIVE, TrainerStatus.UNAVAILABLE);
        assertThat(result).extracting(TrainerResponse::displayName)
                .containsExactly("陳小華", "林小明");
    }

    @Test
    void listPublic_沒有教練時回空清單() {
        when(trainers.findByDeletedAtIsNull()).thenReturn(List.of());

        assertThat(service.listPublic()).isEmpty();
    }

    // === getById() ==============================================================

    @Test
    void getById_成功回傳公開資料() {
        Trainer trainer = trainerOf(activeEmployee(), TrainerStatus.UNAVAILABLE);
        when(trainers.findById(TRAINER_ID)).thenReturn(Optional.of(trainer));

        TrainerResponse response = service.getById(TRAINER_ID);

        assertThat(response.trainerId()).isEqualTo(TRAINER_ID);
        assertThat(response.displayName()).isEqualTo("陳小華");
        // UNAVAILABLE 教練仍可被查到，由前端依 status 決定是否停用預約入口
        assertThat(response.status()).isEqualTo(TrainerStatus.UNAVAILABLE);
    }

    @Test
    void getById_不存在時丟ResourceNotFound() {
        when(trainers.findById(TRAINER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(TRAINER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_已軟刪除時丟ResourceNotFound() {
        Trainer trainer = trainerOf(activeEmployee(), TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findById(TRAINER_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.getById(TRAINER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === assertAvailableForNewBusiness()：§9 跨模組防護共用規則 =====================
    // 被 CoachSlotService/CoachBookingService/GroupClassService 呼叫，四個條件
    // （Trainer 未軟刪除、Employee 未軟刪除、Employee ACTIVE、Trainer ACTIVE）
    // 任一不成立就拒絕。這裡直接測四個條件本身；三個呼叫端只驗證「有沒有在正確時機呼叫」，
    // 判斷邏輯不重複測（見各自檔案的對應測試方法註解）。

    @Test
    void assertAvailable_全部條件成立時不拋例外() {
        Trainer trainer = trainerOf(activeEmployee(), TrainerStatus.ACTIVE);

        assertThatCode(() -> service.assertAvailableForNewBusiness(trainer)).doesNotThrowAnyException();
    }

    @Test
    void assertAvailable_Trainer已軟刪除時拒絕() {
        Trainer trainer = trainerOf(activeEmployee(), TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());

        assertThatThrownBy(() -> service.assertAvailableForNewBusiness(trainer))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void assertAvailable_Employee已軟刪除時拒絕() {
        Employee employee = activeEmployee();
        employee.softDelete();
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);

        assertThatThrownBy(() -> service.assertAvailableForNewBusiness(trainer))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void assertAvailable_Employee非ACTIVE時拒絕() {
        Employee employee = activeEmployee();
        employee.setStatus(EmployeeStatus.INACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);

        assertThatThrownBy(() -> service.assertAvailableForNewBusiness(trainer))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void assertAvailable_TrainerUNAVAILABLE時拒絕() {
        Trainer trainer = trainerOf(activeEmployee(), TrainerStatus.UNAVAILABLE);

        assertThatThrownBy(() -> service.assertAvailableForNewBusiness(trainer))
                .isInstanceOf(BusinessRuleException.class);
    }

    // === lockAndAssertAvailableForNewBusiness()：§9 跨模組防護正確用法 ==============
    // code review 發現（2026-08-19）：先前版本讓呼叫端各自傳入已讀到的（未鎖定）Trainer，
    // 存在競態條件——ADMIN 停用教練時會悲觀鎖定 Trainer 列才檢查未完成業務
    // （見 AdminTrainerService.suspend()），但新增業務這邊完全沒有鎖，兩個交易可能
    // 交錯執行成「新增業務讀到 ACTIVE → ADMIN 鎖定並檢查（新業務還沒 commit，查不到）
    // → 停用成功 → 新增業務才真正 commit」，讓已停用教練仍產生新排班／預約／場次。
    // 這個方法改成內部自己用 findByIdForUpdate() 悲觀鎖定，跟 suspend() 搶同一顆鎖，
    // 兩邊只要有一邊先取得鎖，另一邊就會被擋到前者交易 commit 之後才能繼續。

    @Test
    void lockAndAssertAvailable_以findByIdForUpdate悲觀鎖定而非一般查詢() {
        Trainer trainer = trainerOf(activeEmployee(), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));

        service.lockAndAssertAvailableForNewBusiness(TRAINER_ID);

        verify(trainers).findByIdForUpdate(TRAINER_ID);
        verify(trainers, never()).findById(TRAINER_ID);
    }

    @Test
    void lockAndAssertAvailable_全部條件成立時回傳鎖定後的Trainer() {
        Trainer trainer = trainerOf(activeEmployee(), TrainerStatus.ACTIVE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));

        Trainer result = service.lockAndAssertAvailableForNewBusiness(TRAINER_ID);

        assertThat(result).isSameAs(trainer);
    }

    @Test
    void lockAndAssertAvailable_找不到時丟ResourceNotFound() {
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void lockAndAssertAvailable_鎖定後讀到UNAVAILABLE時仍拒絕() {
        Trainer trainer = trainerOf(activeEmployee(), TrainerStatus.UNAVAILABLE);
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void lockAndAssertAvailable_鎖定後讀到已軟刪除時仍拒絕() {
        Trainer trainer = trainerOf(activeEmployee(), TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findByIdForUpdate(TRAINER_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .isInstanceOf(BusinessRuleException.class);
    }
}
