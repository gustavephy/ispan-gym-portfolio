package tw.com.ispan.gym.dto.member;

import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.member.Member;

public record MemberRegisterResponse(
        Integer memberId,
        String firstName,
        String lastName,
        String email,
        String phone,
        String status,
        LocalDateTime createdAt) {

    public static MemberRegisterResponse from(Member member) {
        return new MemberRegisterResponse(
                member.getMemberId(),
                member.getFirstName(),
                member.getLastName(),
                member.getAccount().getEmail(),
                member.getPhone(),
                member.getStatus() != null ? member.getStatus().name() : null,
                member.getCreatedAt());
    }
}
