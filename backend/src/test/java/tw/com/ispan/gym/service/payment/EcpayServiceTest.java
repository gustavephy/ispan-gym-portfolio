package tw.com.ispan.gym.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.entity.payment.PaymentWebhook;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderStatusHistoryRepository;
import tw.com.ispan.gym.repository.payment.PaymentTransactionRepository;
import tw.com.ispan.gym.repository.payment.PaymentWebhookRepository;
import tw.com.ispan.gym.service.order.OrderService;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 簽章部分刻意不寫死任何預期雜湊值——那只會是把實作再抄一遍的套套邏輯。
 * 改為驗證對外可觀察的性質：自家產生的簽章驗得過、任一欄位被竄改就驗不過、
 * 參數順序不影響結果（內部有排序）、ActionUrl 不列入簽章（附加資訊，加在算完之後）。
 *
 * 通知處理涵蓋冪等（重複 eventKey 直接略過）、驗簽失敗與查無訂單時只記錄不履約、
 * 付款成功／失敗的狀態分流，以及成功時才呼叫 fulfillPaidOrder。
 */
class EcpayServiceTest {

    private static final String MERCHANT_ID = "2000132";
    private static final String HASH_KEY = "5294y06JbISpM5x9";
    private static final String HASH_IV = "v77hoKGq4kWxNNIS";
    private static final String TRADE_NO = "GYM20260816120000001";

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository orderItems = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistories = mock(OrderStatusHistoryRepository.class);
    private final PaymentTransactionRepository paymentTransactions = mock(PaymentTransactionRepository.class);
    private final PaymentWebhookRepository paymentWebhooks = mock(PaymentWebhookRepository.class);
    private final OrderService orderService = mock(OrderService.class);

    private final EcpayService service = new EcpayService(MERCHANT_ID, HASH_KEY, HASH_IV,
            "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5",
            "https://example.com/api/payments/ecpay/notify",
            "https://example.com/order-done",
            orders, orderItems, orderStatusHistories, paymentTransactions, paymentWebhooks, orderService);

    // === 共用測試資料 ========================================================

    private Order order(String total) {
        Account account = new Account();
        account.setEmail("buyer@example.com");
        Member member = new Member();
        member.setId(1);
        member.setAccount(account);
        Order order = new Order(member, TRADE_NO, "ORD20260816120000001", new BigDecimal(total),
                BigDecimal.ZERO, new BigDecimal(total), new BigDecimal(total), null, "王小明",
                "0912345678", null, "buyer@example.com");
        ReflectionTestUtils.setField(order, "id", 900);
        return order;
    }

    private OrderItem item(Order order, String name, String price) {
        Product product = new Product();
        product.setProductId(100);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        OrderItem item = new OrderItem(order, product, name, new BigDecimal(price), 1);
        ReflectionTestUtils.setField(item, "id", 1);
        return item;
    }

    private PaymentTransaction payment(Order order) {
        return new PaymentTransaction(order, "ECPAY", TRADE_NO, order.getTotalAmount(), "TWD");
    }

    /** 綠界通知的最小 payload，簽章由自家演算法補上（等同綠界用同一組金鑰簽出來的結果） */
    private Map<String, String> notifyParams(String rtnCode) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("MerchantID", MERCHANT_ID);
        params.put("MerchantTradeNo", TRADE_NO);
        params.put("TradeNo", "2604161200001234");
        params.put("RtnCode", rtnCode);
        params.put("RtnMsg", "1".equals(rtnCode) ? "Succeeded" : "Failed");
        params.put("PaymentType", "Credit_CreditCard");
        params.put("TradeAmt", "1000");
        params.put("CheckMacValue", signatureOf(params));
        return params;
    }

    /** 借 buildCheckoutForm 以外的路徑取得簽章：對同一組參數，驗證用與產生用是同一套演算法 */
    private String signatureOf(Map<String, String> params) {
        return (String) ReflectionTestUtils.invokeMethod(service, "generateCheckMacValue", params);
    }

    private void stubWebhookSaveReturnsArgument() {
        when(paymentWebhooks.save(any(PaymentWebhook.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // === buildCheckoutForm() =================================================

    @Test
    void 結帳表單帶齊綠界必要欄位() {
        Order order = order("1000");
        when(orderItems.findAllByOrderIdOrderByIdAsc(900)).thenReturn(List.of(item(order, "乳清蛋白", "1000")));

        Map<String, String> form = service.buildCheckoutForm(order);

        assertThat(form).containsKeys("MerchantID", "MerchantTradeNo", "MerchantTradeDate", "PaymentType",
                "TotalAmount", "TradeDesc", "ItemName", "ReturnURL", "ChoosePayment", "CheckMacValue");
        assertThat(form.get("MerchantID")).isEqualTo(MERCHANT_ID);
        assertThat(form.get("MerchantTradeNo")).isEqualTo(TRADE_NO);
    }

    @Test
    void 金額四捨五入為整數字串綠界不收小數() {
        Order order = order("1000.60");
        when(orderItems.findAllByOrderIdOrderByIdAsc(900)).thenReturn(List.of());

        Map<String, String> form = service.buildCheckoutForm(order);

        assertThat(form.get("TotalAmount")).isEqualTo("1001");
    }

    @Test
    void 多筆明細的商品名以井字號串接() {
        Order order = order("1500");
        when(orderItems.findAllByOrderIdOrderByIdAsc(900)).thenReturn(List.of(
                item(order, "乳清蛋白", "1000"), item(order, "搖搖杯", "500")));

        Map<String, String> form = service.buildCheckoutForm(order);

        assertThat(form.get("ItemName")).isEqualTo("乳清蛋白#搖搖杯");
    }

    @Test
    void 沒有明細時商品名退回訂單編號() {
        Order order = order("1500");
        when(orderItems.findAllByOrderIdOrderByIdAsc(900)).thenReturn(List.of());

        Map<String, String> form = service.buildCheckoutForm(order);

        assertThat(form.get("ItemName")).isEqualTo("訂單ORD20260816120000001");
    }

    @Test
    void 表單簽章排除ActionUrl因為那是算完才附加的目的地資訊() {
        Order order = order("1000");
        when(orderItems.findAllByOrderIdOrderByIdAsc(900)).thenReturn(List.of());

        Map<String, String> form = service.buildCheckoutForm(order);

        // 連同 ActionUrl 一起驗會失敗（它不在簽章範圍內）
        assertThat(service.verifyCheckMacValue(form)).isFalse();
        // 拿掉 ActionUrl 後就驗得過，證明簽章當下確實不含它
        Map<String, String> signedPart = new LinkedHashMap<>(form);
        signedPart.remove("ActionUrl");
        assertThat(service.verifyCheckMacValue(signedPart)).isTrue();
    }

    // === verifyCheckMacValue() ===============================================

    @Test
    void 自家產生的簽章驗得過() {
        assertThat(service.verifyCheckMacValue(notifyParams("1"))).isTrue();
    }

    @Test
    void 任一欄位被竄改就驗不過() {
        Map<String, String> tampered = notifyParams("1");
        tampered.put("TradeAmt", "1"); // 金額被改小

        assertThat(service.verifyCheckMacValue(tampered)).isFalse();
    }

    @Test
    void 缺少簽章欄位直接視為無效() {
        Map<String, String> params = notifyParams("1");
        params.remove("CheckMacValue");

        assertThat(service.verifyCheckMacValue(params)).isFalse();
    }

    @Test
    void 簽章比對不分大小寫() {
        Map<String, String> params = notifyParams("1");
        params.put("CheckMacValue", params.get("CheckMacValue").toLowerCase());

        assertThat(service.verifyCheckMacValue(params)).isTrue();
    }

    @Test
    void 參數傳入順序不影響簽章結果() {
        Map<String, String> ordered = notifyParams("1");
        // 換成雜湊順序的 Map，內部排序後應該得到同一組簽章
        Map<String, String> shuffled = new HashMap<>(ordered);

        assertThat(service.verifyCheckMacValue(shuffled)).isTrue();
    }

    // === handleNotify()：冪等與錯誤處理 ========================================

    @Test
    void 重複的通知直接略過不重複履約() {
        when(paymentWebhooks.existsByProviderAndEventKey("ECPAY", TRADE_NO + "-1")).thenReturn(true);

        service.handleNotify(notifyParams("1"));

        verify(paymentWebhooks, never()).save(any());
        verify(orders, never()).findByMerchantTradeNo(any());
        verify(orderService, never()).fulfillPaidOrder(any());
    }

    @Test
    void 驗簽失敗只記錄錯誤不動訂單() {
        stubWebhookSaveReturnsArgument();
        Map<String, String> tampered = notifyParams("1");
        tampered.put("TradeAmt", "1");

        service.handleNotify(tampered);

        verify(paymentWebhooks).save(org.mockito.ArgumentMatchers.argThat(
                (PaymentWebhook w) -> "ERROR".equals(w.getProcessingStatus())
                        && Boolean.FALSE.equals(w.getCheckMacValid())));
        verify(orders, never()).findByMerchantTradeNo(any());
        verify(orderService, never()).fulfillPaidOrder(any());
    }

    @Test
    void 查無對應訂單只記錄錯誤不履約() {
        stubWebhookSaveReturnsArgument();
        when(orders.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.empty());

        service.handleNotify(notifyParams("1"));

        verify(paymentWebhooks).save(org.mockito.ArgumentMatchers.argThat(
                (PaymentWebhook w) -> "ERROR".equals(w.getProcessingStatus())
                        && w.getErrorMessage().contains(TRADE_NO)));
        verify(orderService, never()).fulfillPaidOrder(any());
    }

    // === handleNotify()：付款成功／失敗 ========================================

    @Test
    void 付款成功轉為已付款並觸發履約() {
        stubWebhookSaveReturnsArgument();
        Order order = order("1000");
        PaymentTransaction payment = payment(order);
        when(orders.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.of(order));
        when(paymentTransactions.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.of(payment));

        service.handleNotify(notifyParams("1"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(payment.getStatus()).isEqualTo("SUCCESS");
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(payment.getTradeNo()).isEqualTo("2604161200001234");
        assertThat(payment.getRtnCode()).isEqualTo(1);
        verify(orderService).fulfillPaidOrder(order);
    }

    @Test
    void 付款失敗轉為失敗且不履約() {
        stubWebhookSaveReturnsArgument();
        Order order = order("1000");
        PaymentTransaction payment = payment(order);
        when(orders.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.of(order));
        when(paymentTransactions.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.of(payment));

        service.handleNotify(notifyParams("0"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(payment.getStatus()).isEqualTo("FAILED");
        assertThat(payment.getPaidAt()).isNull();
        verify(orderService, never()).fulfillPaidOrder(any());
    }

    @Test
    void 沒有付款紀錄時自動補建一筆() {
        stubWebhookSaveReturnsArgument();
        Order order = order("1000");
        when(orders.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.of(order));
        when(paymentTransactions.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.empty());
        when(paymentTransactions.save(any(PaymentTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.handleNotify(notifyParams("1"));

        verify(paymentTransactions).save(org.mockito.ArgumentMatchers.argThat(
                (PaymentTransaction p) -> p.getOrder() == order && TRADE_NO.equals(p.getMerchantTradeNo())));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void 處理完成後標記通知為已處理() {
        stubWebhookSaveReturnsArgument();
        Order order = order("1000");
        when(orders.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.of(order));
        when(paymentTransactions.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.of(payment(order)));

        service.handleNotify(notifyParams("1"));

        verify(paymentWebhooks).save(org.mockito.ArgumentMatchers.argThat(
                (PaymentWebhook w) -> "PROCESSED".equals(w.getProcessingStatus()) && w.getProcessedAt() != null));
    }

    @Test
    void 非數字的回傳代碼不會讓處理中斷() {
        stubWebhookSaveReturnsArgument();
        Order order = order("1000");
        PaymentTransaction payment = payment(order);
        when(orders.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.of(order));
        when(paymentTransactions.findByMerchantTradeNo(TRADE_NO)).thenReturn(Optional.of(payment));
        Map<String, String> params = notifyParams("X");

        service.handleNotify(params);

        // 解析失敗記為 null，流程照走完並視為付款失敗
        assertThat(payment.getRtnCode()).isNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    }
}
