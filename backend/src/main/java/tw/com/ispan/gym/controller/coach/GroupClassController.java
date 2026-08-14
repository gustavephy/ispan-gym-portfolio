package tw.com.ispan.gym.controller.coach;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coach.GroupClassSessionResponse;
import tw.com.ispan.gym.service.coach.GroupClassService;

@RestController
@RequestMapping("/api/member/groupClasses")
@RequiredArgsConstructor
public class GroupClassController {

    private final GroupClassService groupClassService;

    /**
     * 查看即將開始、開放報名的團課場次。刻意不加 @PreAuthorize（不限定要有 MEMBER 角色），
     * 跟 CoachBookingController.searchSlots() 的既有取捨一致：瀏覽課表本身不該要求登入權限，
     * 只有實際「報名」的動作才需要驗證身分。
     * 註：這跟 searchSlots() 目前留著的同一個 TODO 有一樣的前提——SecurityConfig 尚未補上
     * 對應的 permitAll() 規則前，這支端點實際上可能還是會被預設的登入檢查擋下來，
     * 這是既有的專案層級待辦事項，不是這次新產生的問題。
     */
    @GetMapping
    public ResponseEntity<List<GroupClassSessionResponse>> getUpcomingSessions() {
        List<GroupClassSessionResponse> sessions = groupClassService.getUpcomingSessions();
        return ResponseEntity.status(HttpStatus.OK).body(sessions);
    }

    
}