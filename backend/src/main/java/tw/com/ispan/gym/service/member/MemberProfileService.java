package tw.com.ispan.gym.service.member;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.member.MemberProfileResponse;
import tw.com.ispan.gym.dto.member.MemberProfileUpdateRequest;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 會員自助查詢／修改個人資料。
 *
 * <p>accountId 一律由呼叫端（Controller）從登入資訊解出並傳入，Service 不依賴
 * SecurityContext，也不注入 CurrentUserProvider——職責切分為「Controller 決定
 * 目前登入者是誰」、「Service 決定這個 account 能否讀取／修改 Member」。
 *
 * <p>務必只傳 accountId，不能傳 memberId：updateMyProfile() 靠 findByAccountIdForUpdate()
 * 確保 Member 在這個 transaction 裡只被載入一次（悲觀鎖那一次）。若改傳 memberId
 * 且呼叫端是透過 requireCurrentMemberId() 取得（該方法內部會先用未鎖定查詢載入 Member），
 * 就會重新踩回 identity map 陷阱——鎖到了資料庫的列，但 Java 物件仍是先前那個未鎖定讀到的舊值。
 */
@Service
@Transactional(readOnly = true)
public class MemberProfileService {

    private final MemberRepository members;

    public MemberProfileService(MemberRepository members) {
        this.members = members;
    }

    public MemberProfileResponse getMyProfile(Integer accountId) {
        Member member = members.findByAccountId(accountId)
                .filter(m -> m.getDeletedAt() == null)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有可用的會員資料"));
        return MemberProfileResponse.from(member);
    }

    @Transactional
    public MemberProfileResponse updateMyProfile(Integer accountId, MemberProfileUpdateRequest request) {
        Member member = members.findByAccountIdForUpdate(accountId)
                .filter(m -> m.getDeletedAt() == null && m.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有可用的會員資料"));

        member.setFirstName(request.getFirstName());
        member.setLastName(request.getLastName());
        member.setPhone(request.getPhone());
        member.setDob(request.getDob());
        member.setGender(request.getGender());
        member.setEmergencyContact(request.getEmergencyContact());
        member.setEmergencyPhone(request.getEmergencyPhone());
        member.setZipCode(request.getZipCode());
        member.setCity(request.getCity());
        member.setDistrict(request.getDistrict());
        member.setAddress(request.getAddress());

        return MemberProfileResponse.from(member);
    }
}
