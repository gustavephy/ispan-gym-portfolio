package tw.com.ispan.gym.controller.member;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tw.com.ispan.gym.dto.member.MemberSelfResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;

/**
 * 會員自身基本資料查詢。目前只有 memberId 一個用途：聊天室（14.3）的 room_id
 * 格式固定內嵌 memberId（如 member_5_ai），前端必須先知道自己的 member_id
 * 才能組出要訂閱的 STOMP 目的地——REST 端點內部都靠 CurrentUserProvider 反查、
 * 不需要前端帶 memberId，但 WebSocket 訂閱是前端主動組字串，這裡補上缺的一環。
 */
@RestController
public class MemberSelfController {
    private final CurrentUserProvider currentUser;

    public MemberSelfController(CurrentUserProvider currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/api/members/me")
    public MemberSelfResponse getSelf() {
        return new MemberSelfResponse(currentUser.requireCurrentMemberId());
    }
}
