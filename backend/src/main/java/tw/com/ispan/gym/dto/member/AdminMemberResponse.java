package tw.com.ispan.gym.dto.member;

import java.time.LocalDate;
import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.member.Gender;
import tw.com.ispan.gym.enums.member.UserStatus;

/** ADMIN 單筆查詢用。不回傳 accountId——目前沒有端點需要它，email 已足以識別帳號。 */
public record AdminMemberResponse(
        Integer memberId,
        String email,
        String firstName,
        String lastName,
        String phone,
        LocalDate dob,
        Gender gender,
        String emergencyContact,
        String emergencyPhone,
        String zipCode,
        String city,
        String district,
        String address,
        String avatarUrl,
        UserStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AdminMemberResponse from(Member member) {
        return new AdminMemberResponse(
                member.getId(),
                member.getAccount().getEmail(),
                member.getFirstName(),
                member.getLastName(),
                member.getPhone(),
                member.getDob(),
                member.getGender(),
                member.getEmergencyContact(),
                member.getEmergencyPhone(),
                member.getZipCode(),
                member.getCity(),
                member.getDistrict(),
                member.getAddress(),
                member.getAvatarUrl(),
                member.getStatus(),
                member.getCreatedAt(),
                member.getUpdatedAt());
    }
}
