package tw.com.ispan.gym.service.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import tw.com.ispan.gym.common.idempotency.IdempotencyRequest;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.repository.idempotency.IdempotencyRequestRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 純 Mockito 單元測試。刻意不重寫服務內部的 SHA-256 雜湊演算法來預先算好期望值
 * （那只是把實作再抄一遍），改用 ArgumentCaptor 抓下第一次呼叫真正存下的記錄，
 * 餵回下一次 findBy... 的查詢結果，驗證的是「重送會不會被擋下/回放」這個對外行為，
 * 不是雜湊的計算結果本身。
 */
class IdempotencyServiceTest {

    private static final Integer MEMBER_ID = 1;
    private static final String OPERATION = "CHECKOUT_CART";
    private static final String KEY = "idem-key-001";

    private final IdempotencyRequestRepository requests = mock(IdempotencyRequestRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final IdempotencyService service = new IdempotencyService(requests, members);

    @SuppressWarnings("unchecked")
    private Supplier<Integer> mockAction() {
        return mock(Supplier.class);
    }

    private void stubNoExistingRecord() {
        when(requests.findByMemberIdAndOperationNameAndIdempotencyKey(MEMBER_ID, OPERATION, KEY))
                .thenReturn(Optional.empty());
        when(members.getReferenceById(MEMBER_ID)).thenReturn(new Member());
    }

    @Test
    void 沒有帶金鑰時直接執行不建立任何記錄() {
        Supplier<Integer> action = mockAction();
        when(action.get()).thenReturn(99);

        Integer result = service.resolve(MEMBER_ID, OPERATION, null, "payload", action);

        assertThat(result).isEqualTo(99);
        verify(requests, never()).findByMemberIdAndOperationNameAndIdempotencyKey(any(), any(), any());
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void 空白金鑰視同沒有帶金鑰() {
        Supplier<Integer> action = mockAction();
        when(action.get()).thenReturn(1);

        service.resolve(MEMBER_ID, OPERATION, "   ", "payload", action);

        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void 首次呼叫建立記錄並執行action回傳resourceId() {
        stubNoExistingRecord();
        when(requests.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        Supplier<Integer> action = mockAction();
        when(action.get()).thenReturn(55);

        Integer result = service.resolve(MEMBER_ID, OPERATION, KEY, "payload", action);

        assertThat(result).isEqualTo(55);
        verify(action, times(1)).get();
    }

    @Test
    void 相同金鑰重送且內容相同時直接回傳先前結果不重複執行() {
        stubNoExistingRecord();
        ArgumentCaptor<IdempotencyRequest> captor = ArgumentCaptor.forClass(IdempotencyRequest.class);
        when(requests.saveAndFlush(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        Supplier<Integer> action = mockAction();
        when(action.get()).thenReturn(123);

        Integer first = service.resolve(MEMBER_ID, OPERATION, KEY, "payload-A", action);
        assertThat(first).isEqualTo(123);

        // 第二次請求查到第一次留下、已經是 COMPLETED 狀態的那筆記錄
        IdempotencyRequest saved = captor.getValue();
        when(requests.findByMemberIdAndOperationNameAndIdempotencyKey(MEMBER_ID, OPERATION, KEY))
                .thenReturn(Optional.of(saved));

        Integer second = service.resolve(MEMBER_ID, OPERATION, KEY, "payload-A", action);

        assertThat(second).isEqualTo(123);
        verify(action, times(1)).get(); // 沒有因為重送而再被呼叫一次
    }

    @Test
    void 相同金鑰但請求內容不同時視為誤用而擋下() {
        stubNoExistingRecord();
        ArgumentCaptor<IdempotencyRequest> captor = ArgumentCaptor.forClass(IdempotencyRequest.class);
        when(requests.saveAndFlush(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        Supplier<Integer> action = mockAction();
        when(action.get()).thenReturn(1);
        service.resolve(MEMBER_ID, OPERATION, KEY, "payload-A", action);

        IdempotencyRequest saved = captor.getValue();
        when(requests.findByMemberIdAndOperationNameAndIdempotencyKey(MEMBER_ID, OPERATION, KEY))
                .thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.resolve(MEMBER_ID, OPERATION, KEY, "payload-B", action))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("另一筆內容不同的請求");
        verify(action, times(1)).get(); // 還是只有第一次真的執行過
    }

    @Test
    void 查到還在處理中尚未完成的相同金鑰記錄時擋下() {
        stubNoExistingRecord();
        ArgumentCaptor<IdempotencyRequest> captor = ArgumentCaptor.forClass(IdempotencyRequest.class);
        when(requests.saveAndFlush(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        Supplier<Integer> failingAction = mockAction();
        when(failingAction.get()).thenThrow(new RuntimeException("模擬處理到一半中斷"));

        // action 執行途中掛掉，記錄留在建立時的 PENDING 狀態，沒機會轉 COMPLETED
        assertThatThrownBy(() -> service.resolve(MEMBER_ID, OPERATION, KEY, "payload-A", failingAction))
                .isInstanceOf(RuntimeException.class);

        IdempotencyRequest stillPending = captor.getValue();
        when(requests.findByMemberIdAndOperationNameAndIdempotencyKey(MEMBER_ID, OPERATION, KEY))
                .thenReturn(Optional.of(stillPending));

        Supplier<Integer> action = mockAction();
        assertThatThrownBy(() -> service.resolve(MEMBER_ID, OPERATION, KEY, "payload-A", action))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("正在處理中");
        verify(action, never()).get();
    }

    @Test
    void 併發同時搶插入同一把金鑰時擋下() {
        stubNoExistingRecord();
        when(requests.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("重複金鑰"));
        Supplier<Integer> action = mockAction();

        assertThatThrownBy(() -> service.resolve(MEMBER_ID, OPERATION, KEY, "payload-A", action))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("正在處理中");
        verify(action, never()).get();
    }
}
