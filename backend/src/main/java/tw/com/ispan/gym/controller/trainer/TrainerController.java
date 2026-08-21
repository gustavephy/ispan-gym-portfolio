package tw.com.ispan.gym.controller.trainer;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.trainer.TrainerChatDirectoryResponse;
import tw.com.ispan.gym.dto.trainer.TrainerCreateRequest;
import tw.com.ispan.gym.dto.trainer.TrainerResponse;
import tw.com.ispan.gym.service.trainer.TrainerService;

/**
 * 建立、公開查詢與聊天室用查詢分屬不同路徑前綴（/api/employees/{id}/trainer、
 * /api/trainers、/api/member/trainers/chat-directory），故不設 class 層級的
 * @RequestMapping，各方法自帶完整路徑。
 *
 * <p>建立限 ADMIN：SecurityConfig 有對應 matcher，方法層級再加一次 @PreAuthorize 雙重把關，
 * 比照 EmployeeReviewController 既有風格。兩個公開 GET 是教練介紹頁用，
 * SecurityConfig 已 permitAll，這裡刻意不加任何權限 annotation。
 * chat-directory 沒有對應的 SecurityConfig permitAll matcher，落回 anyRequest().authenticated()，
 * 再由 @PreAuthorize("hasRole('MEMBER')") narrow 到會員限定，比照 CoachBookingController 的既有風格。
 */
@RestController
public class TrainerController {

    private final TrainerService trainerService;

    public TrainerController(TrainerService trainerService) {
        this.trainerService = trainerService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/employees/{employeeId}/trainer")
    @ResponseStatus(HttpStatus.CREATED)
    public TrainerResponse create(@PathVariable Integer employeeId,
            @Valid @RequestBody TrainerCreateRequest request) {
        return trainerService.create(employeeId, request);
    }

    @GetMapping("/api/trainers")
    public List<TrainerResponse> list() {
        return trainerService.listPublic();
    }

    @GetMapping("/api/trainers/{trainerId}")
    public TrainerResponse getById(@PathVariable Integer trainerId) {
        return trainerService.getById(trainerId);
    }

    @PreAuthorize("hasRole('MEMBER')")
    @GetMapping("/api/member/trainers/chat-directory")
    public List<TrainerChatDirectoryResponse> listForChat() {
        return trainerService.listForChat();
    }
}
