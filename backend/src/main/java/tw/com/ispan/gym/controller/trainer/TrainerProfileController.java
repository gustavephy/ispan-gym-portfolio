package tw.com.ispan.gym.controller.trainer;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.trainer.TrainerProfileResponse;
import tw.com.ispan.gym.dto.trainer.TrainerProfileUpdateRequest;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.trainer.TrainerProfileService;

/**
 * 教練本人查詢／修改專業資料。一般個資（姓名／電話）不在這裡，改走既有的
 * EmployeeProfileController（/api/employee/profile）。
 *
 * <p>GET／PUT 都掛 hasRole('TRAINER')——比照 EmployeeProfileController 用 hasRole('EMPLOYEE')
 * 而非 .authenticated() 的理由：ROLE_TRAINER 由 AccountAuthorityResolver 算出「員工 ACTIVE
 * 且有對應未軟刪除的 Trainer 資料列」才授予，若只要求已登入，同帳號若還有其他啟用中身分
 * 就可能繞過「沒有有效教練身分」的限制。SecurityConfig 的 /api/trainer/** 已是
 * hasRole("TRAINER")，這裡的方法層級 @PreAuthorize 是第二層防護。
 *
 * <p>GET 用 requireCurrentEmployeeId()：hasRole('TRAINER') 已在安全層保證資格，GET 沒有
 * 後續鎖定步驟。PUT 用 requireCurrentUserId()（只回傳 accountId，不在 Controller 層載入
 * 任何 entity），Service 內部才解出 employeeId 並鎖定 Trainer，避免 identity map 陷阱。
 */
@RestController
@RequestMapping("/api/trainer")
public class TrainerProfileController {

    private final TrainerProfileService profileService;
    private final CurrentUserProvider currentUser;

    public TrainerProfileController(TrainerProfileService profileService, CurrentUserProvider currentUser) {
        this.profileService = profileService;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasRole('TRAINER')")
    @GetMapping("/profile")
    public TrainerProfileResponse getMyProfile() {
        Integer employeeId = currentUser.requireCurrentEmployeeId();
        return profileService.getMyProfile(employeeId);
    }

    @PreAuthorize("hasRole('TRAINER')")
    @PutMapping("/profile")
    public TrainerProfileResponse updateMyProfile(@Valid @RequestBody TrainerProfileUpdateRequest request) {
        Integer accountId = currentUser.requireCurrentUserId();
        return profileService.updateMyProfile(accountId, request);
    }
}
