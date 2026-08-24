package tw.com.ispan.gym.service.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.trainer.TrainerProfileResponse;
import tw.com.ispan.gym.dto.trainer.TrainerProfileUpdateRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 純 Mockito 單元測試（比照 EmployeeProfileServiceTest），不連資料庫。
 * accountId／employeeId 一律由呼叫端直接傳入，「目前登入者是誰」是 Controller 的職責。
 */
class TrainerProfileServiceTest {

    private static final Integer ACCOUNT_ID = 5;
    private static final Integer EMPLOYEE_ID = 3;
    private static final Integer TRAINER_ID = 7;

    private final TrainerRepository trainers = mock(TrainerRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final TrainerProfileService service = new TrainerProfileService(trainers, employees);

    private Employee employeeOf(EmployeeStatus status) {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("trainer@example.com");

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

    private TrainerProfileUpdateRequest validRequest() {
        TrainerProfileUpdateRequest request = new TrainerProfileUpdateRequest();
        request.setSpecialty("重量訓練、運動恢復");
        request.setExperience("健身教練六年");
        request.setBio("專注於肌力、體態及運動恢復");
        request.setProfileImageUrl("https://example.com/trainer/12-new.jpg");
        return request;
    }

    // === getMyProfile() ==========================================================

    @Test
    void getMyProfile_ACTIVE教練查自己_回傳完整資料() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        TrainerProfileResponse response = service.getMyProfile(EMPLOYEE_ID);

        assertThat(response.trainerId()).isEqualTo(TRAINER_ID);
        assertThat(response.employeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(response.specialty()).isEqualTo("重量訓練");
        assertThat(response.status()).isEqualTo(TrainerStatus.ACTIVE);
    }

    /** UNAVAILABLE 仍是有效教練身分，本人查詢不受影響——只有 deletedAt／Employee 狀態才會擋 */
    @Test
    void getMyProfile_UNAVAILABLE教練仍可查自己() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.UNAVAILABLE);
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        TrainerProfileResponse response = service.getMyProfile(EMPLOYEE_ID);

        assertThat(response.status()).isEqualTo(TrainerStatus.UNAVAILABLE);
    }

    @Test
    void getMyProfile_Trainer已軟刪除_丟ForbiddenAccess() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.getMyProfile(EMPLOYEE_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void getMyProfile_Employee已軟刪除_丟ForbiddenAccess() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        employee.setDeletedAt(LocalDateTime.now());
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.getMyProfile(EMPLOYEE_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void getMyProfile_EmployeeINACTIVE_丟ForbiddenAccess() {
        Employee employee = employeeOf(EmployeeStatus.INACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.getMyProfile(EMPLOYEE_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void getMyProfile_沒有Trainer資料_丟ForbiddenAccess() {
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyProfile(EMPLOYEE_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    // === updateMyProfile() =======================================================

    /**
     * 必須先用 accountId 解出 employeeId（不鎖定，只是查 id），再以 employeeId 悲觀鎖定 Trainer——
     * 不能對同一個 Trainer 先做未鎖定查詢再鎖定（identity map 陷阱），但這裡解出的是
     * Employee 的 id，跟接下來鎖定的 Trainer 是不同實體，故不會踩到這個問題。
     */
    @Test
    void updateMyProfile_以employeeId悲觀鎖定Trainer() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        service.updateMyProfile(ACCOUNT_ID, validRequest());

        verify(trainers).findByEmployeeIdForUpdate(EMPLOYEE_ID);
        verify(trainers, never()).findByEmployeeEmployeeId(any());
    }

    @Test
    void updateMyProfile_成功寫入專業資料並去除前後空白() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));
        TrainerProfileUpdateRequest request = validRequest();
        request.setSpecialty("  重量訓練、運動恢復  ");

        TrainerProfileResponse response = service.updateMyProfile(ACCOUNT_ID, request);

        assertThat(response.specialty()).isEqualTo("重量訓練、運動恢復");
        assertThat(response.experience()).isEqualTo("健身教練六年");
        assertThat(response.profileImageUrl()).isEqualTo("https://example.com/trainer/12-new.jpg");
    }

    @Test
    void updateMyProfile_圖片網址空白字串轉為null() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));
        TrainerProfileUpdateRequest request = validRequest();
        request.setProfileImageUrl("   ");

        TrainerProfileResponse response = service.updateMyProfile(ACCOUNT_ID, request);

        assertThat(response.profileImageUrl()).isNull();
    }

    @Test
    void updateMyProfile_不會更動status或trainerId() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.UNAVAILABLE);
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        TrainerProfileResponse response = service.updateMyProfile(ACCOUNT_ID, validRequest());

        assertThat(response.status()).isEqualTo(TrainerStatus.UNAVAILABLE);
        assertThat(response.trainerId()).isEqualTo(TRAINER_ID);
    }

    @Test
    void updateMyProfile_鎖定後讀到Trainer已軟刪除時仍拒絕() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        trainer.setDeletedAt(LocalDateTime.now());
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void updateMyProfile_鎖定後讀到Employee非ACTIVE時仍拒絕() {
        Employee employee = employeeOf(EmployeeStatus.INACTIVE);
        Trainer trainer = trainerOf(employee, TrainerStatus.ACTIVE);
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void updateMyProfile_帳號沒有對應員工資料時丟ForbiddenAccess() {
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
        verify(trainers, never()).findByEmployeeIdForUpdate(any());
    }

    @Test
    void updateMyProfile_員工沒有Trainer資料時丟ForbiddenAccess() {
        Employee employee = employeeOf(EmployeeStatus.ACTIVE);
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeIdForUpdate(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }
}
