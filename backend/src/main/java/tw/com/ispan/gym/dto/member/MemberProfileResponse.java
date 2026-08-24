package tw.com.ispan.gym.dto.member;

import java.time.LocalDate;
import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.member.Gender;
import tw.com.ispan.gym.enums.member.UserStatus;

/** 會員自助查詢用。email／avatarUrl 為唯讀欄位，不可透過 MemberProfileUpdateRequest 修改。 */
public record MemberProfileResponse(
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
        LocalDateTime createdAt) {

    public static MemberProfileResponse from(Member member) {
        return new MemberProfileResponse(
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
                member.getCreatedAt());
    }
}
