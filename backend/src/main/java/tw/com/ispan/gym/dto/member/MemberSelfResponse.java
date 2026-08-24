package tw.com.ispan.gym.dto.member;

/** 登入會員查詢自己的 member_id，供前端組裝聊天室 room_id（member_{memberId}_ai 等）使用。 */
public record MemberSelfResponse(Integer memberId) {
}
