package tw.com.ispan.gym.dto.member;

import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.member.UserStatus;

/** ADMIN 會員列表用，刻意精簡，不夾帶地址／緊急聯絡人等 PII。 */
public record AdminMemberSummaryResponse(
        Integer memberId,
        String displayName,
        String email,
        String phone,
        UserStatus status,
        LocalDateTime createdAt) {

    public static AdminMemberSummaryResponse from(Member member) {
        String displayName = member.getLastName() + member.getFirstName();
        return new AdminMemberSummaryResponse(
                member.getId(),
                displayName,
                member.getAccount().getEmail(),
                member.getPhone(),
                member.getStatus(),
                member.getCreatedAt());
    }
}
