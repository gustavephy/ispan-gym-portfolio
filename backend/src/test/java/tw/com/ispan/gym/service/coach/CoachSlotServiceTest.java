package tw.com.ispan.gym.service.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import tw.com.ispan.gym.dto.coach.CoachSlotResponse;
import tw.com.ispan.gym.dto.coach.CreateCoachSlotRequest;
import tw.com.ispan.gym.dto.coach.UpdateCoachSlotRequest;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.entity.coach.CoachSlot;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.coach.SlotStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ConfirmationRequiredException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coach.CoachBookingRepository;
import tw.com.ispan.gym.repository.coach.CoachSlotRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;

/**
 * 純 Mockito 單元測試（沿用 InbodyServiceTest 的做法：專案無內嵌測試資料庫，
 * 不用 @SpringBootTest，直接 mock Repository）。
 *
 * 涵蓋：account→employee→trainer 兩段身分解析、重疊檢查、本人檢查、
 * 兩階段確認（BOOKED 需 acknowledge）、markAsBooked 的狀態守衛
 * （防雙重預約三層防線中的應用層那一層）、markAsAvailable 對 CANCELLED 的安靜跳過。
 * 另外兩層防線（CoachSlot @Version 樂觀鎖、DB 過濾式唯一索引）需要真實資料庫，
 * 不在本測試範圍。
 */
class CoachSlotServiceTest {

    private static final Integer ACCOUNT_ID = 100;
    private static final Integer EMPLOYEE_ID = 200;
    private static final Integer TRAINER_ID = 1;
    private static final Integer OTHER_TRAINER_ID = 2;
    private static final Integer SLOT_ID = 10;
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 9, 1, 11, 0);

    private final CoachSlotRepository slots = mock(CoachSlotRepository.class);
    private final TrainerRepository trainers = mock(TrainerRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final CoachBookingRepository bookings = mock(CoachBookingRepository.class);
    private final TrainerService trainerService = mock(TrainerService.class);
    private final CoachSlotService service = new CoachSlotService(slots, trainers, employees, bookings, trainerService);

    // === 共用測試資料 ========================================================

    private Trainer trainer(Integer trainerId) {
        Trainer trainer = new Trainer();
        trainer.setId(trainerId);
        return trainer;
    }

    /**
     * 讓 ACCOUNT_ID 這個帳號解析成 TRAINER_ID 教練（account→employee→trainer 兩段查詢都放行）。
     * createSlot() 走的是另一條「先解出 id、再鎖定讀取」的路徑（見 CoachSlotService 的說明），
     * 這裡一併把 findIdByEmployeeId 與 mock 的 trainerService.lockAndAssertAvailableForNewBusiness
     * 都準備好，讓成功路徑的測試不用各自重複 stub。
     */
    private Trainer loginAsTrainer() {
        Employee employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        Trainer trainer = trainer(TRAINER_ID);
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(trainer));
        when(trainers.findIdByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(TRAINER_ID));
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID)).thenReturn(trainer);
        return trainer;
    }

    private CoachSlot slot(Trainer trainer, SlotStatus status) {
        CoachSlot slot = new CoachSlot();
        slot.setId(SLOT_ID);
        slot.setTrainer(trainer);
        slot.setStartAt(START);
        slot.setEndAt(END);
        slot.setStatus(status);
        return slot;
    }

    private CreateCoachSlotRequest createRequest() {
        CreateCoachSlotRequest request = new CreateCoachSlotRequest();
        request.setStartAt(START);
        request.setEndAt(END);
        return request;
    }

    private UpdateCoachSlotRequest updateRequest(boolean acknowledge) {
        UpdateCoachSlotRequest request = new UpdateCoachSlotRequest();
        request.setStartAt(START.plusHours(2));
        request.setEndAt(END.plusHours(2));
        request.setAcknowledgeExistingBooking(acknowledge);
        return request;
    }

    private void stubSaveReturnsArgument() {
        when(slots.save(any(CoachSlot.class))).thenAnswer(inv -> {
            CoachSlot s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(SLOT_ID);
            }
            return s;
        });
    }

    // === createSlot =========================================================

    @Test
    void 建立時段_成功且狀態為AVAILABLE() {
        loginAsTrainer();
        when(slots.findOverlapping(TRAINER_ID, START, END)).thenReturn(List.of());
        stubSaveReturnsArgument();

        CoachSlotResponse response = service.createSlot(ACCOUNT_ID, createRequest());

        assertThat(response.getId()).isEqualTo(SLOT_ID);
        assertThat(response.getTrainerId()).isEqualTo(TRAINER_ID);
        assertThat(response.getStartAt()).isEqualTo(START);
        assertThat(response.getEndAt()).isEqualTo(END);
        assertThat(response.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void 建立時段_非員工帳號拒絕() {
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSlot(ACCOUNT_ID, createRequest()))
                .isInstanceOf(ForbiddenAccessException.class)
                .hasMessageContaining("沒有教練身分");
        verify(slots, never()).save(any());
    }

    @Test
    void 建立時段_員工無教練身分拒絕() {
        Employee employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee));
        when(trainers.findIdByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSlot(ACCOUNT_ID, createRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
        verify(slots, never()).save(any());
    }

    @Test
    void 建立時段_教練非可服務狀態擋下且不儲存() {
        // §9 跨模組防護：UNAVAILABLE／已軟刪除教練不可新增排班。TrainerService 本身的
        // 四條件判斷邏輯與悲觀鎖定由 TrainerServiceTest／真實 DB E2E 驗證，這裡只驗證
        // CoachSlotService 有沒有在儲存前確實呼叫這道防護（trainerService 是 mock，
        // 不執行真正的判斷邏輯）。
        loginAsTrainer();
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .thenThrow(new BusinessRuleException("教練目前無法承接新業務（非在職或非可服務狀態）"));

        assertThatThrownBy(() -> service.createSlot(ACCOUNT_ID, createRequest()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("無法承接新業務");
        verify(slots, never()).save(any());
    }

    @Test
    void 建立時段_與既有時段重疊擋下且不儲存() {
        Trainer trainer = loginAsTrainer();
        when(slots.findOverlapping(TRAINER_ID, START, END))
                .thenReturn(List.of(slot(trainer, SlotStatus.AVAILABLE)));

        assertThatThrownBy(() -> service.createSlot(ACCOUNT_ID, createRequest()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("重疊");
        verify(slots, never()).save(any());
    }

    // === cancelSlot =========================================================

    @Test
    void 取消時段_成功軟刪除為CANCELLED() {
        Trainer trainer = loginAsTrainer();
        CoachSlot slot = slot(trainer, SlotStatus.AVAILABLE);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        stubSaveReturnsArgument();

        CoachSlotResponse response = service.cancelSlot(ACCOUNT_ID, SLOT_ID, false);

        assertThat(response.getStatus()).isEqualTo(SlotStatus.CANCELLED);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.CANCELLED);
    }

    @Test
    void 取消時段_查無時段回404() {
        loginAsTrainer();
        when(slots.findById(SLOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelSlot(ACCOUNT_ID, SLOT_ID, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 取消時段_非本人時段拒絕() {
        loginAsTrainer();
        CoachSlot othersSlot = slot(trainer(OTHER_TRAINER_ID), SlotStatus.AVAILABLE);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(othersSlot));

        assertThatThrownBy(() -> service.cancelSlot(ACCOUNT_ID, SLOT_ID, false))
                .isInstanceOf(ForbiddenAccessException.class);
        verify(slots, never()).save(any());
    }

    @Test
    void 取消時段_已被預約未確認回409() {
        Trainer trainer = loginAsTrainer();
        CoachSlot slot = slot(trainer, SlotStatus.BOOKED);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> service.cancelSlot(ACCOUNT_ID, SLOT_ID, false))
                .isInstanceOf(ConfirmationRequiredException.class);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        verify(slots, never()).save(any());
    }

    @Test
    void 取消時段_已被預約且確認後放行() {
        Trainer trainer = loginAsTrainer();
        CoachSlot slot = slot(trainer, SlotStatus.BOOKED);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        stubSaveReturnsArgument();

        CoachSlotResponse response = service.cancelSlot(ACCOUNT_ID, SLOT_ID, true);

        assertThat(response.getStatus()).isEqualTo(SlotStatus.CANCELLED);
    }

    @Test
    void 取消時段_重複取消冪等不報錯() {
        Trainer trainer = loginAsTrainer();
        CoachSlot slot = slot(trainer, SlotStatus.CANCELLED);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        stubSaveReturnsArgument();

        CoachSlotResponse response = service.cancelSlot(ACCOUNT_ID, SLOT_ID, false);

        assertThat(response.getStatus()).isEqualTo(SlotStatus.CANCELLED);
    }

    // === updateSlot =========================================================

    @Test
    void 修改時段_成功更新時間() {
        Trainer trainer = loginAsTrainer();
        CoachSlot slot = slot(trainer, SlotStatus.AVAILABLE);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        UpdateCoachSlotRequest request = updateRequest(false);
        when(slots.findOverlappingExcluding(TRAINER_ID, SLOT_ID, request.getStartAt(), request.getEndAt()))
                .thenReturn(List.of());
        stubSaveReturnsArgument();

        CoachSlotResponse response = service.updateSlot(ACCOUNT_ID, SLOT_ID, request);

        assertThat(response.getStartAt()).isEqualTo(request.getStartAt());
        assertThat(response.getEndAt()).isEqualTo(request.getEndAt());
        // 重疊檢查必須排除自己，否則永遠改不了
        verify(slots).findOverlappingExcluding(TRAINER_ID, SLOT_ID, request.getStartAt(), request.getEndAt());
    }

    @Test
    void 修改時段_已取消不可復活() {
        Trainer trainer = loginAsTrainer();
        CoachSlot slot = slot(trainer, SlotStatus.CANCELLED);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> service.updateSlot(ACCOUNT_ID, SLOT_ID, updateRequest(false)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已被取消");
        verify(slots, never()).save(any());
    }

    @Test
    void 修改時段_已被預約未確認回409() {
        Trainer trainer = loginAsTrainer();
        CoachSlot slot = slot(trainer, SlotStatus.BOOKED);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> service.updateSlot(ACCOUNT_ID, SLOT_ID, updateRequest(false)))
                .isInstanceOf(ConfirmationRequiredException.class);
        verify(slots, never()).save(any());
    }

    @Test
    void 修改時段_新時間重疊擋下() {
        Trainer trainer = loginAsTrainer();
        CoachSlot slot = slot(trainer, SlotStatus.AVAILABLE);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        UpdateCoachSlotRequest request = updateRequest(false);
        when(slots.findOverlappingExcluding(TRAINER_ID, SLOT_ID, request.getStartAt(), request.getEndAt()))
                .thenReturn(List.of(slot(trainer, SlotStatus.AVAILABLE)));

        assertThatThrownBy(() -> service.updateSlot(ACCOUNT_ID, SLOT_ID, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("重疊");
        // 時間不可被改動
        assertThat(slot.getStartAt()).isEqualTo(START);
        verify(slots, never()).save(any());
    }

    // === markAsBooked（防雙重預約的應用層防線） ==============================

    @Test
    void markAsBooked_可預約時段轉為BOOKED() {
        CoachSlot slot = slot(trainer(TRAINER_ID), SlotStatus.AVAILABLE);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        stubSaveReturnsArgument();

        service.markAsBooked(SLOT_ID);

        assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        verify(slots).save(slot);
    }

    @Test
    void markAsBooked_已被預約的時段再標記擋下() {
        CoachSlot slot = slot(trainer(TRAINER_ID), SlotStatus.BOOKED);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> service.markAsBooked(SLOT_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("無法預約");
        verify(slots, never()).save(any());
    }

    @Test
    void markAsBooked_已取消的時段不可預約() {
        CoachSlot slot = slot(trainer(TRAINER_ID), SlotStatus.CANCELLED);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> service.markAsBooked(SLOT_ID))
                .isInstanceOf(BusinessRuleException.class);
        verify(slots, never()).save(any());
    }

    // === markAsAvailable ====================================================

    @Test
    void markAsAvailable_BOOKED轉回AVAILABLE() {
        CoachSlot slot = slot(trainer(TRAINER_ID), SlotStatus.BOOKED);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        stubSaveReturnsArgument();

        service.markAsAvailable(SLOT_ID);

        assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void markAsAvailable_已取消的時段安靜跳過不復活() {
        CoachSlot slot = slot(trainer(TRAINER_ID), SlotStatus.CANCELLED);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));

        service.markAsAvailable(SLOT_ID);

        // 教練已取消的時段不能因會員取消預約而復活成可預約，也不能拋錯害會員的取消整包回滾
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.CANCELLED);
        verify(slots, never()).save(any());
    }

    // === 查詢 ================================================================

    @Test
    void getMySlots_回傳自己的所有時段含CANCELLED() {
        Trainer trainer = loginAsTrainer();
        CoachSlot cancelled = slot(trainer, SlotStatus.CANCELLED);
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "startAt"));
        when(slots.searchMySlots(TRAINER_ID, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(cancelled), pageable, 1));

        PageResponse<CoachSlotResponse> response = service.getMySlots(ACCOUNT_ID, 0, 10, null, null, null);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getStatus()).isEqualTo(SlotStatus.CANCELLED);
    }

    @Test
    void getMySlots_狀態與日期區間正確轉換並傳給查詢() {
        // 這是這批篩選功能真正要保證的事：dateTo 是使用者選的「那一天」，查詢邊界要撐到
        // 隔天 00:00（不含）才會把 dateTo 當天整天都算進去；status 字串（對應表格顯示的四種
        // 狀態，見 CoachSlotRepository#searchMySlots 的說明）要原封不動傳給 repository，
        // PENDING/COMPLETED 這種要靠關聯的 CoachBooking 狀態才分得出來的邏輯在 JPQL 裡，
        // Mockito 對 repository 的 mock 驗證不到，這裡只驗證 Service 層的參數轉換正確。
        Trainer trainer = loginAsTrainer();
        LocalDate dateFrom = LocalDate.of(2026, 9, 1);
        LocalDate dateTo = LocalDate.of(2026, 9, 5);
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "startAt"));
        CoachSlot pending = slot(trainer, SlotStatus.BOOKED);
        when(slots.searchMySlots(TRAINER_ID, "PENDING", LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 6, 0, 0), pageable))
                .thenReturn(new PageImpl<>(List.of(pending), pageable, 1));

        PageResponse<CoachSlotResponse> response = service.getMySlots(ACCOUNT_ID, 0, 10, "PENDING", dateFrom, dateTo);

        assertThat(response.getItems()).hasSize(1);
        verify(slots).searchMySlots(TRAINER_ID, "PENDING", LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 6, 0, 0), pageable);
    }

    @Test
    void searchSlots_只查AVAILABLE且不需教練身分() {
        when(trainers.existsById(TRAINER_ID)).thenReturn(true);
        CoachSlot available = slot(trainer(TRAINER_ID), SlotStatus.AVAILABLE);
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "startAt"));
        when(slots.searchAvailableSlots(TRAINER_ID, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(available), pageable, 1));

        PageResponse<CoachSlotResponse> response = service.searchSlots(TRAINER_ID, 0, 10, null, null);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        // 會員瀏覽任意教練的時段，不應觸發身分解析
        verify(employees, never()).findByAccountId(any());
    }

    @Test
    void searchSlots_教練不存在時回404() {
        when(trainers.existsById(TRAINER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.searchSlots(TRAINER_ID, 0, 10, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
