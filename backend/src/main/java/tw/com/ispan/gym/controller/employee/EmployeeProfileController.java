package tw.com.ispan.gym.controller.employee;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.employee.EmployeeProfileResponse;
import tw.com.ispan.gym.dto.employee.EmployeeProfileUpdateRequest;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.employee.EmployeeProfileService;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import tw.com.ispan.gym.service.file.FileService;

/**
 * 員工自助查詢／修改個人資料。
 *
 * <p>GET／PUT 都掛 hasRole('EMPLOYEE')——跟 MemberProfileController 的 GET 只要求
 * .authenticated() 不同，是刻意的：若只要求已登入，同一帳號若還有其他啟用中身分
 * （例如同時是 ACTIVE 會員），INACTIVE 的員工身分仍會被看到並讀出資料，這正是這次
 * 規格要擋住的行為。hasRole('EMPLOYEE') 才能把員工身分本身是否 ACTIVE 的判斷
 * 跟同帳號其他身分脫鉤。SecurityConfig 的 /api/employee/** 已經是 hasRole("EMPLOYEE")，
 * 這裡的方法層級 @PreAuthorize 是第二層防護，比照 MemberProfileController 既有風格。
 *
 * <p>GET 用 requireCurrentEmployeeId()：hasRole('EMPLOYEE') 已在安全層保證 ACTIVE，
 * 且 GET 沒有後續鎖定步驟，沒有 identity-map 陳舊疑慮。PUT 用 requireCurrentUserId()
 * （只回傳 accountId，不在 Controller 層載入 Employee entity），Service 內以
 * findByAccountIdForUpdate() 一次鎖定——理由同 MemberProfileController。
 */
@RestController
@RequestMapping("/api/employee")
public class EmployeeProfileController {

    private final EmployeeProfileService profileService;
    private final CurrentUserProvider currentUser;
    private final FileService fileService;

    public EmployeeProfileController(EmployeeProfileService profileService, CurrentUserProvider currentUser, FileService fileService) {
        this.profileService = profileService;
        this.currentUser = currentUser;
        this.fileService = fileService;
    }

    @PreAuthorize("hasRole('EMPLOYEE')")
    @PostMapping("/profile/upload")
    public ResponseEntity<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        String fileUrl = fileService.saveImage(file).url();
        return ResponseEntity.ok(Map.of("url", fileUrl));
    }

    @PreAuthorize("hasRole('EMPLOYEE')")
    @GetMapping("/profile")
    public EmployeeProfileResponse getMyProfile() {
        Integer employeeId = currentUser.requireCurrentEmployeeId();
        return profileService.getMyProfile(employeeId);
    }

    @PreAuthorize("hasRole('EMPLOYEE')")
    @PutMapping("/profile")
    public EmployeeProfileResponse updateMyProfile(@Valid @RequestBody EmployeeProfileUpdateRequest request) {
        Integer accountId = currentUser.requireCurrentUserId();
        return profileService.updateMyProfile(accountId, request);
    }
}
