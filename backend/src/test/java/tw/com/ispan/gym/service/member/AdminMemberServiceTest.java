package tw.com.ispan.gym.service.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import tw.com.ispan.gym.dto.member.AdminMemberResponse;
import tw.com.ispan.gym.dto.member.AdminMemberSummaryResponse;
import tw.com.ispan.gym.dto.member.AdminMemberUpdateRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.member.Gender;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.InvalidRequestException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 純 Mockito 單元測試（比照 TrainerServiceTest），不連資料庫。
 * findMembersForAdmin() 已 mock：這裡只驗證 Service 有沒有把參數正確傳給 Repository
 * （keyword trim／正規化、狀態原樣傳遞、分頁與排序組裝），驗不到「查得到」——
 * 那是 JPQL 本身的正確性，留給第 8 步真實 DB E2E。
 */
class AdminMemberServiceTest {

    private static final Integer MEMBER_ID = 3;

    private final MemberRepository members = mock(MemberRepository.class);
    private final AdminMemberService service = new AdminMemberService(members);

    private Member memberOf(UserStatus status) {
        Account account = new Account();
        account.setId(5);
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

    private AdminMemberUpdateRequest validAdminRequest() {
        AdminMemberUpdateRequest request = new AdminMemberUpdateRequest();
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

    // === list()：分頁參數驗證 ====================================================

    @Test
    void list_page小於0時丟InvalidRequest_且不呼叫Repository() {
        assertThatThrownBy(() -> service.list(null, null, -1, 10, null, null))
                .isInstanceOf(InvalidRequestException.class);
        verify(members, never()).findMembersForAdmin(any(), any(), any());
    }

    @Test
    void list_size為0或超過上限時丟InvalidRequest_且不呼叫Repository() {
        assertThatThrownBy(() -> service.list(null, null, 0, 0, null, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, 0, 101, null, null))
                .isInstanceOf(InvalidRequestException.class);
        verify(members, never()).findMembersForAdmin(any(), any(), any());
    }

    @Test
    void list_size為1或100時合法() {
        when(members.findMembersForAdmin(any(), any(), any())).thenReturn(Page.empty());

        assertThatCode(() -> service.list(null, null, 0, 1, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> service.list(null, null, 0, 100, null, null)).doesNotThrowAnyException();
    }

    @Test
    void list_page與size正確組裝進Pageable() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(members.findMembersForAdmin(any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, 2, 25, null, null);

        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    // === list()：keyword／status 傳遞 ==============================================

    @Test
    void list_空白keyword正規化為null() {
        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        when(members.findMembersForAdmin(keywordCaptor.capture(), any(), any())).thenReturn(Page.empty());

        service.list("   ", null, 0, 10, null, null);

        assertThat(keywordCaptor.getValue()).isNull();
    }

    @Test
    void list_關鍵字trim後與狀態原樣傳給Repository() {
        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UserStatus> statusCaptor = ArgumentCaptor.forClass(UserStatus.class);
        when(members.findMembersForAdmin(keywordCaptor.capture(), statusCaptor.capture(), any()))
                .thenReturn(Page.empty());

        service.list("  small@example.com  ", UserStatus.ACTIVE, 0, 10, null, null);

        assertThat(keywordCaptor.getValue()).isEqualTo("small@example.com");
        assertThat(statusCaptor.getValue()).isEqualTo(UserStatus.ACTIVE);
    }

    // === list()：預設排序＋映射 ====================================================

    @Test
    void list_無條件時使用預設排序且正確映射為摘要DTO() {
        Member member = memberOf(UserStatus.ACTIVE);
        Page<Member> page = new PageImpl<>(List.of(member));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(members.findMembersForAdmin(isNull(), isNull(), pageableCaptor.capture())).thenReturn(page);

        Page<AdminMemberSummaryResponse> result = service.list(null, null, 0, 10, null, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.getContent().get(0).displayName()).isEqualTo("王小美");
        assertThat(pageableCaptor.getValue().getSort()).containsExactly(Sort.Order.asc("id"));
    }

    // === list()：排序白名單與次排序 ================================================

    @Test
    void list_sort為null空白或非法值時回退id() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(members.findMembersForAdmin(any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, 0, 10, null, null);
        service.list(null, null, 0, 10, "  ", null);
        service.list(null, null, 0, 10, "unknownField", null);

        for (Pageable pageable : pageableCaptor.getAllValues()) {
            assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("id"));
        }
    }

    @Test
    void list_sort為name時展開為姓名加id次排序() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(members.findMembersForAdmin(any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, 0, 10, "name", null);

        assertThat(pageableCaptor.getValue().getSort()).containsExactly(
                Sort.Order.asc("lastName"),
                Sort.Order.asc("firstName"),
                Sort.Order.asc("id"));
    }

    @Test
    void list_sort為name且direction為desc時姓名降冪但id次排序仍是升冪() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(members.findMembersForAdmin(any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, 0, 10, "name", "desc");

        assertThat(pageableCaptor.getValue().getSort()).containsExactly(
                Sort.Order.desc("lastName"),
                Sort.Order.desc("firstName"),
                Sort.Order.asc("id"));
    }

    @Test
    void list_sort為createdAt時附加id次排序() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(members.findMembersForAdmin(any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, 0, 10, "createdAt", null);

        assertThat(pageableCaptor.getValue().getSort()).containsExactly(
                Sort.Order.asc("createdAt"),
                Sort.Order.asc("id"));
    }

    @Test
    void list_direction為desc或DESC不分大小寫皆生效() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(members.findMembersForAdmin(any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, 0, 10, "id", "desc");
        service.list(null, null, 0, 10, "id", "DESC");

        for (Pageable pageable : pageableCaptor.getAllValues()) {
            assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("id"));
        }
    }

    @Test
    void list_direction為null或非法值時回退asc() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(members.findMembersForAdmin(any(), any(), pageableCaptor.capture())).thenReturn(Page.empty());

        service.list(null, null, 0, 10, "id", null);
        service.list(null, null, 0, 10, "id", "sideways");

        for (Pageable pageable : pageableCaptor.getAllValues()) {
            assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("id"));
        }
    }

    // === getById() ==================================================================

    @Test
    void getById_成功回傳完整資料() {
        Member member = memberOf(UserStatus.ACTIVE);
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        AdminMemberResponse response = service.getById(MEMBER_ID);

        assertThat(response.memberId()).isEqualTo(MEMBER_ID);
        assertThat(response.email()).isEqualTo("member@example.com");
    }

    @Test
    void getById_找不到時丟ResourceNotFound() {
        when(members.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(MEMBER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_已軟刪除時丟ResourceNotFound() {
        Member member = memberOf(UserStatus.ACTIVE);
        member.setDeletedAt(LocalDateTime.now());
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.getById(MEMBER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === update() ====================================================================

    @Test
    void update_成功寫入全部11個欄位_且不影響status_avatarUrl與email() {
        Member member = memberOf(UserStatus.SUSPENDED);
        member.setAvatarUrl("https://example.com/old-avatar.png");
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));

        AdminMemberResponse response = service.update(MEMBER_ID, validAdminRequest());

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

        assertThat(member.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(member.getAvatarUrl()).isEqualTo("https://example.com/old-avatar.png");
        assertThat(member.getAccount().getEmail()).isEqualTo("member@example.com");
    }

    /**
     * PUT 是完整覆蓋語意，可選欄位傳 null 代表清除舊值——跟 MemberProfileUpdateRequest
     * 是同一份契約。這條直接鎖住這件事：若日後有人把 setter 改成「非 null 才更新」，
     * 讓 PUT 偷偷變成 PATCH 語意，這裡會立刻測出來。
     */
    @Test
    void update_可選欄位傳null時清除原值() {
        Member member = memberOf(UserStatus.ACTIVE);
        member.setDob(LocalDate.of(1990, 1, 1));
        member.setGender(Gender.F);
        member.setEmergencyContact("舊聯絡人");
        member.setEmergencyPhone("0911111111");
        member.setZipCode("100");
        member.setCity("台北市");
        member.setDistrict("中正區");
        member.setAddress("舊地址");

        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));

        AdminMemberUpdateRequest request = new AdminMemberUpdateRequest();
        request.setFirstName("小明");
        request.setLastName("林");
        request.setPhone("0933333333");
        // 其餘可選欄位刻意維持 null

        AdminMemberResponse response = service.update(MEMBER_ID, request);

        assertThat(response.dob()).isNull();
        assertThat(response.gender()).isNull();
        assertThat(response.emergencyContact()).isNull();
        assertThat(response.emergencyPhone()).isNull();
        assertThat(response.zipCode()).isNull();
        assertThat(response.city()).isNull();
        assertThat(response.district()).isNull();
        assertThat(response.address()).isNull();
    }

    @Test
    void update_使用悲觀鎖_不呼叫普通findById() {
        Member member = memberOf(UserStatus.ACTIVE);
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));

        service.update(MEMBER_ID, validAdminRequest());

        verify(members).findByIdForUpdate(MEMBER_ID);
        verify(members, never()).findById(MEMBER_ID);
    }

    @Test
    void update_找不到時丟ResourceNotFound() {
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(MEMBER_ID, validAdminRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_已軟刪除時丟ResourceNotFound() {
        Member member = memberOf(UserStatus.ACTIVE);
        member.setDeletedAt(LocalDateTime.now());
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.update(MEMBER_ID, validAdminRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === suspend() / activate()：狀態轉換 6 格 =========================================

    @Test
    void suspend_ACTIVE轉為SUSPENDED() {
        Member member = memberOf(UserStatus.ACTIVE);
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));

        service.suspend(MEMBER_ID);

        assertThat(member.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void suspend_已是SUSPENDED時丟BusinessRule() {
        Member member = memberOf(UserStatus.SUSPENDED);
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.suspend(MEMBER_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void suspend_PENDING時丟BusinessRule() {
        Member member = memberOf(UserStatus.PENDING);
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.suspend(MEMBER_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void suspend_找不到或已軟刪除時丟ResourceNotFound() {
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.suspend(MEMBER_ID)).isInstanceOf(ResourceNotFoundException.class);

        Member deleted = memberOf(UserStatus.ACTIVE);
        deleted.setDeletedAt(LocalDateTime.now());
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> service.suspend(MEMBER_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void activate_SUSPENDED轉為ACTIVE() {
        Member member = memberOf(UserStatus.SUSPENDED);
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));

        service.activate(MEMBER_ID);

        assertThat(member.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void activate_已是ACTIVE時丟BusinessRule() {
        Member member = memberOf(UserStatus.ACTIVE);
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.activate(MEMBER_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void activate_PENDING時丟BusinessRule() {
        Member member = memberOf(UserStatus.PENDING);
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.activate(MEMBER_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void activate_找不到或已軟刪除時丟ResourceNotFound() {
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate(MEMBER_ID)).isInstanceOf(ResourceNotFoundException.class);

        Member deleted = memberOf(UserStatus.SUSPENDED);
        deleted.setDeletedAt(LocalDateTime.now());
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> service.activate(MEMBER_ID)).isInstanceOf(ResourceNotFoundException.class);
    }
}
