package tw.com.ispan.gym.service.trainer;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.trainer.TrainerChatDirectoryResponse;
import tw.com.ispan.gym.dto.trainer.TrainerCreateRequest;
import tw.com.ispan.gym.dto.trainer.TrainerResponse;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * Trainer 建立/查詢：只有 ADMIN 能把 ACTIVE 的 Employee 升級為 Trainer，
 * 不走同儕審核（跟 EmployeeReviewService 是不同動作的不同權限模型）。
 * 一位 Employee 一生只會有一筆 Trainer（revive-not-recreate，DB 有 unconditional unique
 * constraint 兜底），已軟刪除的舊列一律擋掉重複建立，須改走 AdminTrainerService.restore()。
 */
@Service
@Transactional(readOnly = true)
public class TrainerService {

    private final TrainerRepository trainers;
    private final EmployeeRepository employees;

    public TrainerService(TrainerRepository trainers, EmployeeRepository employees) {
        this.trainers = trainers;
        this.employees = employees;
    }

    @Transactional
    public TrainerResponse create(Integer employeeId, TrainerCreateRequest request) {
        // 鎖住 Employee 列，讓同一個 employeeId 的併發建立請求序列化，
        // 第二個請求會等第一個 commit 後才往下查，讀到真正已存在的 Trainer，
        // 才能回報精準的「已經是教練」而不是靠 DB unique constraint 兜底的通用訊息
        Employee employee = employees.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此員工"));

        if (employee.getStatus() != EmployeeStatus.ACTIVE || employee.getDeletedAt() != null) {
            throw new BusinessRuleException("此員工目前不是在職狀態，無法建立教練身分");
        }

        Optional<Trainer> existing = trainers.findByEmployeeEmployeeId(employeeId);
        if (existing.isPresent()) {
            if (existing.get().getDeletedAt() == null) {
                throw new BusinessRuleException("此員工已經是教練");
            }
            throw new BusinessRuleException("此員工先前曾是教練，須改走 ADMIN 恢復流程，不能重新建立");
        }

        Trainer trainer = new Trainer();
        trainer.setEmployee(employee);
        // 姓名／email 是建立當下自 Employee／Account 複製的快照（見 Trainer 實體註解），
        // 這裡一併填入，避免留白到下次 SchemaFixupRunner 重啟才補（見 AdminEmployeeService.update()
        // 對這組快照欄位的同步說明）
        trainer.setFirstName(employee.getFirstName());
        trainer.setLastName(employee.getLastName());
        trainer.setEmail(employee.getAccount().getEmail());
        trainer.setSpecialty(request.getSpecialty().trim());
        trainer.setExperience(request.getExperience().trim());
        trainer.setBio(request.getBio().trim());
        trainer.setProfileImageUrl(normalizeProfileImageUrl(request.getProfileImageUrl()));
        trainer.setStatus(TrainerStatus.ACTIVE);

        return TrainerResponse.from(trainers.save(trainer));
    }

    /** 公開列表：ACTIVE／UNAVAILABLE 皆回傳，只排除軟刪除——不叫 listAvailable，避免讓人誤以為只回傳可預約的教練 */
    public List<TrainerResponse> listPublic() {
        return trainers.findByDeletedAtIsNull().stream()
                .map(TrainerResponse::from)
                .toList();
    }

    public TrainerResponse getById(Integer trainerId) {
        Trainer trainer = trainers.findById(trainerId)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此教練"));
        return TrainerResponse.from(trainer);
    }

    /** 聊天室（§14）選教練用，登入會員限定；跟 listPublic 同一份資料，只是多帶 employeeId 給定址用 */
    public List<TrainerChatDirectoryResponse> listForChat() {
        return trainers.findByDeletedAtIsNull().stream()
                .map(TrainerChatDirectoryResponse::from)
                .toList();
    }

    /**
     * §9 跨模組防護共用：四個條件（Trainer 未軟刪除、Employee 未軟刪除、Employee ACTIVE、
     * Trainer ACTIVE）任一不成立就拒絕，不可只靠前端隱藏按鈕擋（見
     * trainer_management_next_phase 規格基準）。純檢查邏輯，不做任何查詢／鎖定——
     * 呼叫端必須自行確保傳入的 trainer 是這次交易「唯一一次」讀到的資料，不能是
     * 先前已經讀過的舊物件，否則檢查形同虛設。新增業務的入口一律改用下面的
     * {@link #lockAndAssertAvailableForNewBusiness(Integer)}，不要直接呼叫這個方法。
     */
    public void assertAvailableForNewBusiness(Trainer trainer) {
        Employee employee = trainer.getEmployee();
        if (trainer.getDeletedAt() != null
                || employee.getDeletedAt() != null
                || employee.getStatus() != EmployeeStatus.ACTIVE
                || trainer.getStatus() != TrainerStatus.ACTIVE) {
            throw new BusinessRuleException("教練目前無法承接新業務（非在職或非可服務狀態）");
        }
    }

    /**
     * §9 跨模組防護的正確用法：新增排班（CoachSlotService.createSlot）、建立預約
     * （CoachBookingService.createBooking）、建立團課場次（GroupClassService.createSession）
     * 前呼叫，取得「這次交易鎖定後」的最新 Trainer 並檢查四個條件。
     *
     * <p>Code review 發現（2026-08-19）：先前版本讓呼叫端各自把已經讀到（未鎖定）的
     * Trainer 物件傳給 {@link #assertAvailableForNewBusiness(Trainer)}，存在真實的
     * 競態條件——ADMIN 停用教練時是先悲觀鎖定 Trainer 列（見 AdminTrainerService.suspend()）
     * 才檢查未完成業務，但新增業務這邊完全沒有鎖，兩個交易可能交錯執行成：
     * (1) 新增業務讀到 ACTIVE → (2) ADMIN 鎖定並檢查（此時新業務還沒 commit，查不到）
     * → 停用成功 → (3) 新增業務的交易才真正 commit，寫入已停用教練的新排班／預約／場次。
     * 這裡改成方法內自己用 findByIdForUpdate() 悲觀鎖定 Trainer 列，跟 suspend()
     * 用同一顆鎖、同一個順序，兩邊只要有一邊先取得鎖，另一邊就會被擋到前者交易
     * commit 之後才能繼續，鎖定後讀到的一定是最新資料，才能真正堵住這個競態。
     *
     * <p>呼叫端必須把「解出 trainerId」跟「鎖定讀取 Trainer」分成兩個獨立步驟，
     * 中間不能有任何對同一筆 Trainer 的未鎖定讀取——同一個 persistence context 裡，
     * Hibernate 的 identity map 會讓鎖定查詢直接回傳先前那個未鎖定讀到的舊物件，
     * 鎖形同虛設（跟 EmployeeProfileService/TrainerProfileService 說明的陷阱是同一件事）。
     * 若需要用 employeeId 反查 trainerId，改用 {@code TrainerRepository.findIdByEmployeeId}
     * 這種純量投影，不要用會把完整實體放進 persistence context 的查詢方法。
     * 若已經透過某個關聯的 lazy proxy 只存取過 {@code .getId()}（例如
     * {@code coachSlot.getTrainer().getId()}），該關聯還沒被初始化，不算「讀過」，可以放心
     * 把 id 傳進來——這個方法內的鎖定查詢會是第一次真正把資料載入這個 Trainer 物件。
     */
    @Transactional
    public Trainer lockAndAssertAvailableForNewBusiness(Integer trainerId) {
        Trainer trainer = trainers.findByIdForUpdate(trainerId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此教練"));
        assertAvailableForNewBusiness(trainer);
        return trainer;
    }

    private static String normalizeProfileImageUrl(String profileImageUrl) {
        return (profileImageUrl == null || profileImageUrl.isBlank()) ? null : profileImageUrl.trim();
    }
}
