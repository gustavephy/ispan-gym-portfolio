package tw.com.ispan.gym.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.payment.PaymentWebhook;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.payment.PaymentTransactionRepository;
import tw.com.ispan.gym.repository.payment.PaymentWebhookRepository;
import tw.com.ispan.gym.service.order.OrderService;

/**
 * 純 Mockito 單元測試（沿用 CoachBookingServiceTest 的做法）。
 *
 * {@link #sign} 用反射呼叫 EcpayService 內部真正的簽章私法，而不是在測試裡另外複製一份
 * 演算法邏輯——這樣 handleNotify 相關測試驗的是「簽章驗證有沒有真的接對」，
 * 不會因為兩邊演算法各自寫錯又剛好互相對上而變成偽陽性。
 */
class EcpayServiceTest {

    private static final String MERCHANT_ID = "3002607";
    private static final String HASH_KEY = "testHashKey";
    private static final String HASH_IV = "testHashIv";
    private static final String ACTION_URL = "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5";
    private static final String RETURN_URL = "http://localhost:8080/api/payments/ecpay/notify";
    private static final String CLIENT_BACK_URL = "http://localhost:5173/orders";
    private static final Integer MEMBER_ID = 1;

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository orderItems = mock(OrderItemRepository.class);
    private final PaymentTransactionRepository paymentTransactions = mock(PaymentTransactionRepository.class);
    private final PaymentWebhookRepository paymentWebhooks = mock(PaymentWebhookRepository.class);
    private final OrderService orderService = mock(OrderService.class);

    private final EcpayService ecpayService = new EcpayService(MERCHANT_ID, HASH_KEY, HASH_IV, ACTION_URL,
            RETURN_URL, CLIENT_BACK_URL, orders, orderItems, paymentTransactions, paymentWebhooks, orderService);

    private Order order() {
        Member member = new Member();
        member.setId(MEMBER_ID);
        return new Order(member, "GYM1", "ORD1", BigDecimal.valueOf(999), BigDecimal.ZERO,
                BigDecimal.valueOf(999), BigDecimal.valueOf(999), null, "王小明", "0912345678", null,
                "member@example.com");
    }

    /** 反射呼叫 EcpayService 內部的 generateCheckMacValue，組出測試用的合法簽章 */
    private String sign(Map<String, String> params) throws Exception {
        Method method = EcpayService.class.getDeclaredMethod("generateCheckMacValue", Map.class);
        method.setAccessible(true);
        return (String) method.invoke(ecpayService, params);
    }

    private Map<String, String> signedNotifyParams(String merchantTradeNo, String tradeNo, String rtnCode)
            throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("MerchantTradeNo", merchantTradeNo);
        params.put("TradeNo", tradeNo);
        params.put("RtnCode", rtnCode);
        params.put("RtnMsg", "Succeeded");
        params.put("PaymentType", "Credit_CreditCard");
        params.put("CheckMacValue", sign(params));
        return params;
    }

    // === buildCheckoutForm / verifyCheckMacValue ==============================

    @Test
    void 組表單_基本欄位正確且簽章可自我驗證() {
        Order order = order();
        when(orderItems.findAllByOrderIdOrderByIdAsc(any())).thenReturn(List.of());

        Map<String, String> form = ecpayService.buildCheckoutForm(order);

        assertThat(form.get("MerchantID")).isEqualTo(MERCHANT_ID);
        assertThat(form.get("MerchantTradeNo")).isEqualTo("GYM1");
        assertThat(form.get("TotalAmount")).isEqualTo("999");
        assertThat(form.get("ReturnURL")).isEqualTo(RETURN_URL);
        assertThat(form.get("ClientBackURL")).isEqualTo(CLIENT_BACK_URL);
        assertThat(form.get("ActionUrl")).isEqualTo(ACTION_URL);
        // ActionUrl 是簽完章才附加的本地端資訊，不是 ECPay 欄位，驗簽前要先拿掉
        // （呼應 buildCheckoutForm 的實作註解：「簽章算完才加，不影響 CheckMacValue」）
        Map<String, String> signedFields = new HashMap<>(form);
        signedFields.remove("ActionUrl");
        assertThat(ecpayService.verifyCheckMacValue(signedFields)).isTrue();
    }

    @Test
    void 驗簽_缺少CheckMacValue回false() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("RtnCode", "1");

        assertThat(ecpayService.verifyCheckMacValue(params)).isFalse();
    }

    @Test
    void 驗簽_竄改參數後簽章不符() {
        Order order = order();
        when(orderItems.findAllByOrderIdOrderByIdAsc(any())).thenReturn(List.of());
        Map<String, String> signedFields = new HashMap<>(ecpayService.buildCheckoutForm(order));
        signedFields.remove("ActionUrl");
        // 竄改前應先驗證這組欄位本來是合法的，否則測試無法區分「本來就不合法」與「被竄改」
        assertThat(ecpayService.verifyCheckMacValue(signedFields)).isTrue();

        signedFields.put("TotalAmount", "1");

        assertThat(ecpayService.verifyCheckMacValue(signedFields)).isFalse();
    }

    // === handleNotify ==========================================================

    @Test
    void 付款通知_簽章正確且RtnCode1時標記已付款並觸發履約() throws Exception {
        Order order = order();
        when(paymentWebhooks.existsByProviderAndEventKey(any(), any())).thenReturn(false);
        when(paymentWebhooks.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orders.findByMerchantTradeNo("GYM1")).thenReturn(Optional.of(order));
        when(paymentTransactions.findByMerchantTradeNo("GYM1")).thenReturn(Optional.empty());
        when(paymentTransactions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ecpayService.handleNotify(signedNotifyParams("GYM1", "T999", "1"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderService).fulfillPaidOrder(order);
    }

    @Test
    void 付款通知_重複通知時略過不重複履約() {
        when(paymentWebhooks.existsByProviderAndEventKey(any(), any())).thenReturn(true);

        ecpayService.handleNotify(Map.of("MerchantTradeNo", "GYM1", "RtnCode", "1"));

        verify(orderService, never()).fulfillPaidOrder(any());
        verify(paymentWebhooks, never()).save(any());
    }

    @Test
    void 付款通知_簽章錯誤時不履約且webhook記錄ERROR() {
        when(paymentWebhooks.existsByProviderAndEventKey(any(), any())).thenReturn(false);
        PaymentWebhook webhook = new PaymentWebhook("ECPAY", "GYM1-1", "GYM1", "T1", "payload", false);
        when(paymentWebhooks.save(any())).thenReturn(webhook);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("MerchantTradeNo", "GYM1");
        params.put("RtnCode", "1");
        params.put("CheckMacValue", "BOGUS");

        ecpayService.handleNotify(params);

        assertThat(webhook.getProcessingStatus()).isEqualTo("ERROR");
        verify(orders, never()).findByMerchantTradeNo(any());
        verify(orderService, never()).fulfillPaidOrder(any());
    }

    @Test
    void 付款通知_找不到對應訂單時記錄ERROR且不拋例外() throws Exception {
        when(paymentWebhooks.existsByProviderAndEventKey(any(), any())).thenReturn(false);
        PaymentWebhook webhook = new PaymentWebhook("ECPAY", "GYM404-1", "GYM404", "T1", "payload", true);
        when(paymentWebhooks.save(any())).thenReturn(webhook);
        when(orders.findByMerchantTradeNo("GYM404")).thenReturn(Optional.empty());

        ecpayService.handleNotify(signedNotifyParams("GYM404", "T1", "1"));

        assertThat(webhook.getProcessingStatus()).isEqualTo("ERROR");
        verify(orderService, never()).fulfillPaidOrder(any());
    }

    @Test
    void 付款通知_RtnCode非1時標記付款失敗且不履約() throws Exception {
        Order order = order();
        when(paymentWebhooks.existsByProviderAndEventKey(any(), any())).thenReturn(false);
        when(paymentWebhooks.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orders.findByMerchantTradeNo("GYM1")).thenReturn(Optional.of(order));
        when(paymentTransactions.findByMerchantTradeNo("GYM1")).thenReturn(Optional.empty());
        when(paymentTransactions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ecpayService.handleNotify(signedNotifyParams("GYM1", "T1", "10"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(orderService, never()).fulfillPaidOrder(any());
    }
}
