package tw.com.ispan.gym.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import tw.com.ispan.gym.dto.payment.AdminPaymentTransactionResponse;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.repository.payment.PaymentRefundRepository;
import tw.com.ispan.gym.repository.payment.PaymentTransactionRepository;
import tw.com.ispan.gym.repository.payment.PaymentWebhookRepository;

/** 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。 */
class AdminPaymentQueryServiceTest {

    private final PaymentTransactionRepository transactions = mock(PaymentTransactionRepository.class);
    private final PaymentRefundRepository refunds = mock(PaymentRefundRepository.class);
    private final PaymentWebhookRepository webhooks = mock(PaymentWebhookRepository.class);
    private final AdminPaymentQueryService service = new AdminPaymentQueryService(transactions, refunds, webhooks);

    private final Pageable pageable = PageRequest.of(0, 20);

    private PaymentTransaction transaction() {
        Order order = new Order(null, "GYM1", "ORD1", new BigDecimal("1000"), BigDecimal.ZERO,
                new BigDecimal("1000"), new BigDecimal("1000"), null, "王小明", "0912345678", null, null);
        ReflectionTestUtils.setField(order, "id", 900);
        PaymentTransaction t = new PaymentTransaction(order, "ECPAY", "GYM1", new BigDecimal("1000"), "TWD");
        ReflectionTestUtils.setField(t, "id", 1);
        return t;
    }

    @Test
    void 交易搜尋_關鍵字轉小寫包萬用字元_空白條件視為不篩() {
        when(transactions.search(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(transaction())));

        Page<AdminPaymentTransactionResponse> result =
                service.searchTransactions("  ", " ECPAY ", null, null, "GYM-Test", pageable);

        verify(transactions).search(isNull(), eq("ECPAY"), isNull(), isNull(), eq("%gym-test%"), eq(pageable));
        assertThat(result.getContent()).singleElement()
                .satisfies(dto -> {
                    assertThat(dto.orderId()).isEqualTo(900);
                    assertThat(dto.merchantTradeNo()).isEqualTo("GYM1");
                });
    }

    @Test
    void 退款搜尋_條件正規化() {
        when(refunds.search(any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.searchRefunds(null, null, null, "  ", pageable);

        verify(refunds).search(isNull(), isNull(), isNull(), isNull(), eq(pageable));
    }

    @Test
    void Webhook搜尋_帶driversMac過濾條件透傳() {
        when(webhooks.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.searchWebhooks("ERROR", null, Boolean.FALSE, null, null, null, pageable);

        verify(webhooks).search(eq("ERROR"), isNull(), eq(Boolean.FALSE), isNull(), isNull(), isNull(), eq(pageable));
    }
}
