package tw.com.ispan.gym.controller.coach;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coach.CreateGroupClassSessionRequest;
import tw.com.ispan.gym.dto.coach.UpdateGroupClassSessionRequest;
import tw.com.ispan.gym.dto.coach.GroupClassSessionResponse;
import tw.com.ispan.gym.service.coach.GroupClassService;

/**
 * 後台團課場次管理。受 SecurityConfig 既有的 /api/admin/** → hasRole("ADMIN") 規則保護，
 * 這裡額外疊上 @PreAuthorize，跟 /api/trainer、/api/member 兩支的慣例一致（雙層防護）。
 */
@RestController
@RequestMapping("/api/admin/group-classes")
@RequiredArgsConstructor
public class AdminGroupClassController {

    private final GroupClassService groupClassService;

    /** 建立團課場次，成功回 201。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GroupClassSessionResponse> createSession(
            @Valid @RequestBody CreateGroupClassSessionRequest request) {
        GroupClassSessionResponse groupClass = groupClassService.createSession(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(groupClass);
    }

    /** 更新團課場次，管理後台專用，需 ADMIN 權限 */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GroupClassSessionResponse> updateSession(@PathVariable Integer id,
            @Valid @RequestBody UpdateGroupClassSessionRequest request) {
        /**
         * 說明：此 API 用於管理後台修改場次，請前端注意以下行為與錯誤處理：
         * - 前端必須在 request 帶入最新的 `version`，後端會以此做快速版本檢查以避免覆蓋。
         * - 若版本不符（有人已修改），Service 會丟出 {@code BusinessRuleException}，建議 Controller
         * 或全域例外處理器將其對應為 HTTP 409（Conflict），前端應在收到 409 時重新載入最新資料後重試。
         * - 若教室時間衝突或其他業務規則被觸發，也會回 409（BusinessRuleException）；404 則表示場次不存在。
         * - 回傳的 DTO 包含 `version` 與 `createdAt`/`updatedAt`，前端應用回來的 `version`
         * 在之後的編輯中再帶回。
         */
        GroupClassSessionResponse resp = groupClassService.updateSession(id, request);
        return ResponseEntity.ok(resp);
    }

    /** 管理者取消整個場次。若場次已有會員報名，預設會拒絕並要求再次確認（query param）。 */
    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GroupClassSessionResponse> cancelSession(@PathVariable Integer sessionId,
            @RequestParam(defaultValue = "false") boolean acknowledgeExistingBooking) {
        GroupClassSessionResponse resp = groupClassService.cancelSession(sessionId, acknowledgeExistingBooking);
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }
}