package tw.com.ispan.gym.service.aihealthchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import tw.com.ispan.gym.dto.aihealthchat.ChatMessageDTO;
import tw.com.ispan.gym.dto.aihealthchat.ChatReplyRequest;
import tw.com.ispan.gym.dto.aihealthchat.ChatSendRequest;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage.ChatCounterpartType;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.aihealthchat.ChatMessageRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 涵蓋：WebSocket 入口只拿得到 account_id、必須自行反查並驗證身分（停權／軟刪除都要擋）、
 * AI 與教練兩種對話的參數互斥規則、房間 id 的組法，以及已讀標記的方向
 * （學員標記對方傳的、員工標記學員傳的——這個布林值傳反了不會報錯但會標錯人，
 * 因此逐一驗證傳入的參數）。
 *
 * AI 回覆是 @Async 的另一個 bean，這裡只驗證「有沒有被觸發」，不驗證回覆內容。
 */
class ChatServiceTest {

    private static final Integer ACCOUNT_ID = 1;
    private static final Integer MEMBER_ID = 10;
    private static final Integer EMPLOYEE_ID = 20;

    private final ChatMessageRepository chatMessages = mock(ChatMessageRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final TrainerRepository trainers = mock(TrainerRepository.class);
    private final ChatMessagePublisher publisher = mock(ChatMessagePublisher.class);
    private final ChatAiResponderService aiResponder = mock(ChatAiResponderService.class);

    private final ChatService service = new ChatService(chatMessages, members, employees, trainers, publisher,
            aiResponder);

    // === 共用測試資料 ========================================================

    private Member member(UserStatus status) {
        Member member = new Member();
        member.setId(MEMBER_ID);
        member.setStatus(status);
        return member;
    }

    private Employee employee(EmployeeStatus status) {
        Employee employee = new Employee();
        ReflectionTestUtils.setField(employee, "employeeId", EMPLOYEE_ID);
        employee.setStatus(status);
        return employee;
    }

    private Trainer activeTrainer() {
        Trainer trainer = new Trainer();
        trainer.setTrainerId(99);
        return trainer;
    }

    private void stubActiveMemberAccount() {
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member(UserStatus.ACTIVE)));
    }

    private void stubActiveEmployeeAccount() {
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
    }

    /** publisher 回傳存檔後的訊息，roomId 由 entity 的 @PrePersist 決定，這裡手動補上 */
    private void stubPublisherEchoes(String roomId) {
        when(publisher.saveAndPush(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage message = inv.getArgument(0);
            ReflectionTestUtils.setField(message, "roomId", roomId);
            return message;
        });
    }

    // === sendFromMember()：AI 對話 =============================================

    @Test
    void 學員傳訊給AI顧問後觸發AI非同步回覆() {
        stubActiveMemberAccount();
        stubPublisherEchoes("member_10_ai");

        service.sendFromMember(ACCOUNT_ID, new ChatSendRequest("我想增肌", ChatCounterpartType.AI, null));

        verify(publisher).saveAndPush(org.mockito.ArgumentMatchers.argThat(
                (ChatMessage m) -> m.getMemberId().equals(MEMBER_ID)
                        && m.getCounterpartType() == ChatCounterpartType.AI
                        && m.getEmployeeId() == null
                        && m.isSenderIsMember()));
        verify(aiResponder).generateChatReplyAsync(MEMBER_ID, "member_10_ai");
    }

    @Test
    void 與AI對話時指定employeeId擋下() {
        stubActiveMemberAccount();

        assertThatThrownBy(() -> service.sendFromMember(ACCOUNT_ID,
                new ChatSendRequest("hi", ChatCounterpartType.AI, EMPLOYEE_ID)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不可指定 employeeId");

        verify(publisher, never()).saveAndPush(any());
    }

    // === sendFromMember()：教練對話 ============================================

    @Test
    void 學員傳訊給教練不觸發AI回覆() {
        stubActiveMemberAccount();
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(activeTrainer()));
        stubPublisherEchoes("member_10_employee_20");

        service.sendFromMember(ACCOUNT_ID,
                new ChatSendRequest("教練好", ChatCounterpartType.EMPLOYEE, EMPLOYEE_ID));

        verify(publisher).saveAndPush(org.mockito.ArgumentMatchers.argThat(
                (ChatMessage m) -> m.getCounterpartType() == ChatCounterpartType.EMPLOYEE
                        && m.getEmployeeId().equals(EMPLOYEE_ID)));
        // 真人對話不該叫 AI 代答
        verify(aiResponder, never()).generateChatReplyAsync(any(), any());
    }

    /**
     * code review 發現（2026-08-19）：聊天目錄只列教練，但 request.employeeId() 是前端
     * 自帶欄位，光靠「ACTIVE 員工」檢查擋不住會員竄改成任意在職員工（例如一般員工或
     * ADMIN，兩者都可能是 ACTIVE 但沒有 Trainer 身分）的 id。這裡驗證修好之後，
     * 沒有有效 Trainer 資料列的 ACTIVE 員工一樣會被擋下。
     */
    @Test
    void 指定的員工是ACTIVE但不是教練時擋下() {
        stubActiveMemberAccount();
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendFromMember(ACCOUNT_ID,
                new ChatSendRequest("hi", ChatCounterpartType.EMPLOYEE, EMPLOYEE_ID)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(publisher, never()).saveAndPush(any());
    }

    @Test
    void 指定的員工是教練但Trainer已軟刪除時擋下() {
        stubActiveMemberAccount();
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
        Trainer deletedTrainer = activeTrainer();
        deletedTrainer.setDeletedAt(java.time.LocalDateTime.now());
        when(trainers.findByEmployeeEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(deletedTrainer));

        assertThatThrownBy(() -> service.sendFromMember(ACCOUNT_ID,
                new ChatSendRequest("hi", ChatCounterpartType.EMPLOYEE, EMPLOYEE_ID)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(publisher, never()).saveAndPush(any());
    }

    @Test
    void 與教練對話未指定employeeId擋下() {
        stubActiveMemberAccount();

        assertThatThrownBy(() -> service.sendFromMember(ACCOUNT_ID,
                new ChatSendRequest("hi", ChatCounterpartType.EMPLOYEE, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("必須指定 employeeId");

        verify(publisher, never()).saveAndPush(any());
    }

    @Test
    void 指定的教練不存在或已離職擋下() {
        stubActiveMemberAccount();

        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sendFromMember(ACCOUNT_ID,
                new ChatSendRequest("hi", ChatCounterpartType.EMPLOYEE, EMPLOYEE_ID)))
                .isInstanceOf(ResourceNotFoundException.class);

        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee(EmployeeStatus.INACTIVE)));
        assertThatThrownBy(() -> service.sendFromMember(ACCOUNT_ID,
                new ChatSendRequest("hi", ChatCounterpartType.EMPLOYEE, EMPLOYEE_ID)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(publisher, never()).saveAndPush(any());
    }

    // === sendFromMember()：身分驗證 ============================================

    @Test
    void 沒有會員身分的帳號不能以學員身分發言() {
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendFromMember(ACCOUNT_ID,
                new ChatSendRequest("hi", ChatCounterpartType.AI, null)))
                .isInstanceOf(ForbiddenAccessException.class)
                .hasMessageContaining("沒有會員身分");
    }

    @Test
    void 停權或已刪除的會員不能發言() {
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member(UserStatus.SUSPENDED)));
        assertThatThrownBy(() -> service.sendFromMember(ACCOUNT_ID,
                new ChatSendRequest("hi", ChatCounterpartType.AI, null)))
                .isInstanceOf(ForbiddenAccessException.class);

        Member deleted = member(UserStatus.ACTIVE);
        deleted.setDeletedAt(LocalDateTime.now());
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> service.sendFromMember(ACCOUNT_ID,
                new ChatSendRequest("hi", ChatCounterpartType.AI, null)))
                .isInstanceOf(ForbiddenAccessException.class);

        verify(publisher, never()).saveAndPush(any());
    }

    // === replyFromEmployee() ==================================================

    @Test
    void 教練回覆學員的訊息標記為非學員發送() {
        stubActiveEmployeeAccount();
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member(UserStatus.ACTIVE)));
        stubPublisherEchoes("member_10_employee_20");

        service.replyFromEmployee(ACCOUNT_ID, new ChatReplyRequest("記得補充蛋白質", MEMBER_ID));

        verify(publisher).saveAndPush(org.mockito.ArgumentMatchers.argThat(
                (ChatMessage m) -> m.getMemberId().equals(MEMBER_ID)
                        && m.getEmployeeId().equals(EMPLOYEE_ID)
                        && m.getCounterpartType() == ChatCounterpartType.EMPLOYEE
                        && !m.isSenderIsMember()));
        // 教練回覆不觸發 AI
        verify(aiResponder, never()).generateChatReplyAsync(any(), any());
    }

    @Test
    void 沒有員工身分的帳號不能以教練身分回覆() {
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replyFromEmployee(ACCOUNT_ID, new ChatReplyRequest("hi", MEMBER_ID)))
                .isInstanceOf(ForbiddenAccessException.class)
                .hasMessageContaining("沒有員工身分");
    }

    @Test
    void 離職員工不能回覆() {
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee(EmployeeStatus.INACTIVE)));

        assertThatThrownBy(() -> service.replyFromEmployee(ACCOUNT_ID, new ChatReplyRequest("hi", MEMBER_ID)))
                .isInstanceOf(ForbiddenAccessException.class);

        verify(publisher, never()).saveAndPush(any());
    }

    @Test
    void 回覆對象學員不存在或已停權擋下() {
        stubActiveEmployeeAccount();

        when(members.findById(MEMBER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replyFromEmployee(ACCOUNT_ID, new ChatReplyRequest("hi", MEMBER_ID)))
                .isInstanceOf(ResourceNotFoundException.class);

        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member(UserStatus.SUSPENDED)));
        assertThatThrownBy(() -> service.replyFromEmployee(ACCOUNT_ID, new ChatReplyRequest("hi", MEMBER_ID)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(publisher, never()).saveAndPush(any());
    }

    // === 歷史查詢與房間 id 組法 =================================================

    @Test
    void 學員查AI對話用AI房間id() {
        when(chatMessages.findByRoomIdOrderByCreatedAtDesc(eq("member_10_ai"), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.getHistoryForMember(MEMBER_ID, ChatCounterpartType.AI, null, 0, 20);

        verify(chatMessages).findByRoomIdOrderByCreatedAtDesc(eq("member_10_ai"), any(Pageable.class));
    }

    @Test
    void 學員查教練對話用教練房間id() {
        when(chatMessages.findByRoomIdOrderByCreatedAtDesc(eq("member_10_employee_20"), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.getHistoryForMember(MEMBER_ID, ChatCounterpartType.EMPLOYEE, EMPLOYEE_ID, 0, 20);

        verify(chatMessages).findByRoomIdOrderByCreatedAtDesc(eq("member_10_employee_20"), any(Pageable.class));
    }

    @Test
    void 學員查教練對話未指定employeeId擋下() {
        assertThatThrownBy(() -> service.getHistoryForMember(MEMBER_ID, ChatCounterpartType.EMPLOYEE, null, 0, 20))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("必須指定 employeeId");
    }

    @Test
    void 學員視角的歷史訊息以學員身分計算是否為自己發送() {
        ChatMessage message = ChatMessage.builder()
                .memberId(MEMBER_ID).counterpartType(ChatCounterpartType.AI)
                .senderIsMember(true).messageContent("hi").build();
        when(chatMessages.findByRoomIdOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(message)));
        when(publisher.toDto(any(), any())).thenReturn(mock(ChatMessageDTO.class));

        service.getHistoryForMember(MEMBER_ID, ChatCounterpartType.AI, null, 0, 20);

        // viewerIsMember=true
        verify(publisher).toDto(any(ChatMessage.class), eq(true));
    }

    @Test
    void 員工視角的歷史訊息以員工身分計算是否為自己發送() {
        stubActiveEmployeeAccount();
        ChatMessage message = ChatMessage.builder()
                .memberId(MEMBER_ID).counterpartType(ChatCounterpartType.EMPLOYEE)
                .employeeId(EMPLOYEE_ID).senderIsMember(true).messageContent("hi").build();
        when(chatMessages.findByRoomIdOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(message)));
        when(publisher.toDto(any(), any())).thenReturn(mock(ChatMessageDTO.class));

        service.getHistoryForEmployeeAccount(ACCOUNT_ID, MEMBER_ID, 0, 20);

        verify(publisher).toDto(any(ChatMessage.class), eq(false));
    }

    // === 已讀標記：方向不可搞反 =================================================

    @Test
    void 學員標記已讀是標對方傳來的訊息() {
        service.markReadForMember(MEMBER_ID, ChatCounterpartType.AI, null);

        // bySenderIsMember=false：標記「不是學員傳的」那些，也就是 AI／教練傳的
        verify(chatMessages).markRoomAsRead("member_10_ai", false);
    }

    @Test
    void 員工標記已讀是標學員傳來的訊息() {
        stubActiveEmployeeAccount();

        service.markReadForEmployeeAccount(ACCOUNT_ID, MEMBER_ID);

        // bySenderIsMember=true：標記學員傳的
        verify(chatMessages).markRoomAsRead("member_10_employee_20", true);
    }

    @Test
    void 員工查詢未指定學員擋下() {
        stubActiveEmployeeAccount();

        assertThatThrownBy(() -> service.markReadForEmployeeAccount(ACCOUNT_ID, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("memberId");
    }

    // === 未讀數 ===============================================================

    @Test
    void 學員未讀數只算對方傳來且未讀的() {
        when(chatMessages.countByMemberIdAndSenderIsMemberFalseAndReadFalse(MEMBER_ID)).thenReturn(3L);

        assertThat(service.unreadCountForMember(MEMBER_ID)).isEqualTo(3L);
    }

    @Test
    void 員工未讀數只算學員傳來且未讀的() {
        stubActiveEmployeeAccount();
        when(chatMessages.countByEmployeeIdAndSenderIsMemberTrueAndReadFalse(EMPLOYEE_ID)).thenReturn(5L);

        assertThat(service.unreadCountForEmployeeAccount(ACCOUNT_ID)).isEqualTo(5L);
    }

    // === 課程推薦 =============================================================

    @Test
    void 課程推薦推播到學員的AI房間且立即回傳() {
        service.requestCourseRecommendation(MEMBER_ID);

        // 只觸發非同步生成，不等待結果、也不先寫入任何訊息
        verify(aiResponder).generateCourseRecommendationAsync(MEMBER_ID, "member_10_ai");
        verify(publisher, never()).saveAndPush(any());
    }
}
