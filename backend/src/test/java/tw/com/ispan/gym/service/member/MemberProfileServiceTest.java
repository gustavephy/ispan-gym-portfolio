package tw.com.ispan.gym.service.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.member.MemberProfileResponse;
import tw.com.ispan.gym.dto.member.MemberProfileUpdateRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.member.Gender;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 純 Mockito 單元測試（比照 TrainerServiceTest），不連資料庫。
 * accountId 一律由呼叫端直接傳入——Service 不注入 CurrentUserProvider，
 * 「目前登入者是誰」是 Controller 的職責，不在這裡測。
 */
class MemberProfileServiceTest {

    private static final Integer ACCOUNT_ID = 5;
    private static final Integer MEMBER_ID = 3;

    private final MemberRepository members = mock(MemberRepository.class);
    private final MemberProfileService service = new MemberProfileService(members);

    private Member memberOf(UserStatus status) {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("member@example.com");

        Member member = new Member();
        member.setId(MEMBER_ID);
        member.setAccount(account);
        member.setFirstName("小美");
        member.setLastName("王");
        member.setPhone("0922222222");
        member.setStatus(status);
        member.setCreatedAt(LocalDateTime.now());
        return member;
    }

    private MemberProfileUpdateRequest validRequest() {
        MemberProfileUpdateRequest request = new MemberProfileUpdateRequest();
        request.setFirstName("小明");
        request.setLastName("林");
        request.setPhone("0933333333");
        request.setDob(LocalDate.of(1990, 1, 1));
        request.setGender(Gender.F);
        request.setEmergencyContact("陳小華");
        request.setEmergencyPhone("0244556677");
        request.setZipCode("100");
        request.setCity("台北市");
        request.setDistrict("中正區");
        request.setAddress("忠孝東路一段1號");
        return request;
    }

    // === getMyProfile() ==========================================================

    @Test
    void getMyProfile_ACTIVE會員查自己_回傳完整資料() {
        Member member = memberOf(UserStatus.ACTIVE);
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        MemberProfileResponse response = service.getMyProfile(ACCOUNT_ID);

        assertThat(response.memberId()).isEqualTo(MEMBER_ID);
        assertThat(response.email()).isEqualTo("member@example.com");
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
    }

    /** 停權會員仍應能查到自己的資料與狀態，不是靠一律擋掉非 ACTIVE 的檢查 */
    @Test
    void getMyProfile_SUSPENDED會員查自己_仍可成功() {
        Member member = memberOf(UserStatus.SUSPENDED);
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        MemberProfileResponse response = service.getMyProfile(ACCOUNT_ID);

        assertThat(response.status()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void getMyProfile_PENDING會員查自己_仍可成功() {
        Member member = memberOf(UserStatus.PENDING);
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        MemberProfileResponse response = service.getMyProfile(ACCOUNT_ID);

        assertThat(response.status()).isEqualTo(UserStatus.PENDING);
    }

    @Test
    void getMyProfile_帳號無會員身分時丟ForbiddenAccess() {
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyProfile(ACCOUNT_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void getMyProfile_已軟刪除的會員_丟ForbiddenAccess() {
        Member member = memberOf(UserStatus.ACTIVE);
        member.setDeletedAt(LocalDateTime.now());
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.getMyProfile(ACCOUNT_ID))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    // === updateMyProfile() =======================================================

    /**
     * 必須經由 account id 悲觀鎖，不能先用任何未鎖定查詢載入同一個 Member（identity map 陷阱：
     * 先讀過的舊物件即使之後鎖定命中同一列，Hibernate 也不會用新結果覆蓋欄位值）。
     */
    @Test
    void updateMyProfile_以account_id悲觀鎖讀取_不預先載入Member() {
        Member member = memberOf(UserStatus.ACTIVE);
        when(members.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(member));

        service.updateMyProfile(ACCOUNT_ID, validRequest());

        verify(members).findByAccountIdForUpdate(ACCOUNT_ID);
        verify(members, never()).findByAccountId(any());
        verify(members, never()).findByIdForUpdate(any());
        verify(members, never()).findById(any());
    }

    @Test
    void updateMyProfile_成功寫入全部11個欄位() {
        Member member = memberOf(UserStatus.ACTIVE);
        when(members.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(member));

        MemberProfileResponse response = service.updateMyProfile(ACCOUNT_ID, validRequest());

        assertThat(response.firstName()).isEqualTo("小明");
        assertThat(response.lastName()).isEqualTo("林");
        assertThat(response.phone()).isEqualTo("0933333333");
        assertThat(response.dob()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(response.gender()).isEqualTo(Gender.F);
        assertThat(response.emergencyContact()).isEqualTo("陳小華");
        assertThat(response.emergencyPhone()).isEqualTo("0244556677");
        assertThat(response.zipCode()).isEqualTo("100");
        assertThat(response.city()).isEqualTo("台北市");
        assertThat(response.district()).isEqualTo("中正區");
        assertThat(response.address()).isEqualTo("忠孝東路一段1號");
    }

    /** 鎖後重新檢查 ACTIVE——即使呼叫端已通過外層授權，鎖到的當下狀態才是準 */
    @Test
    void updateMyProfile_鎖定後讀到SUSPENDED時仍拒絕() {
        Member member = memberOf(UserStatus.SUSPENDED);
        when(members.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void updateMyProfile_鎖定後讀到PENDING時仍拒絕() {
        Member member = memberOf(UserStatus.PENDING);
        when(members.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void updateMyProfile_鎖定後讀到已軟刪除時仍拒絕() {
        Member member = memberOf(UserStatus.ACTIVE);
        member.setDeletedAt(LocalDateTime.now());
        when(members.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void updateMyProfile_找不到會員資料時丟ForbiddenAccess() {
        when(members.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMyProfile(ACCOUNT_ID, validRequest()))
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void updateMyProfile_不會更動status_且avatarUrl維持原值() {
        Member member = memberOf(UserStatus.ACTIVE);
        member.setAvatarUrl("https://example.com/old-avatar.png");
        when(members.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(member));

        service.updateMyProfile(ACCOUNT_ID, validRequest());

        assertThat(member.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(member.getAvatarUrl()).isEqualTo("https://example.com/old-avatar.png");
    }
}
