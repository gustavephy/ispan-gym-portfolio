package tw.com.ispan.gym.controller.coach;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coach.CreateGroupClassSessionRequest;
import tw.com.ispan.gym.dto.coach.GroupClassSessionResponse;
import tw.com.ispan.gym.service.coach.GroupClassService;

/**
 * 後台團課場次管理。受 SecurityConfig 既有的 /api/admin/** → hasRole("ADMIN") 規則保護，
 * 這裡額外疊上 @PreAuthorize，跟 /api/trainer、/api/member 兩支的慣例一致（雙層防護）。
 */
@RestController
@RequestMapping("/api/admin/groupClasses")
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
}