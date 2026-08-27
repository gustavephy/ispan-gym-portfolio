package tw.com.ispan.gym.service.payment;

import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.entity.order.OrderStatusHistory;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.entity.payment.PaymentWebhook;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderStatusHistoryRepository;
import tw.com.ispan.gym.repository.payment.PaymentTransactionRepository;
import tw.com.ispan.gym.repository.payment.PaymentWebhookRepository;
import tw.com.ispan.gym.service.order.OrderService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 綠界 ECPay 全方位金流串接。
 *
 * <p>{@link #buildCheckoutForm} 組出 AioCheckOut 需要送給綠界的欄位（含 CheckMacValue）。
 * 前端拿到後組成隱藏表單自動送出，導向綠界付款頁（見 frontend {@code utils/ecpay.js}）。
 *
 * <p>{@link #handleNotify} 處理綠界的付款結果通知（server-to-server callback）。
 * 本機開發沒有對外公開的 URL，綠界連不進來——這時改用 {@link #simulatePaymentSuccess}
 * （ADMIN 專用、僅在 {@code app.payment.simulate-enabled=true} 時可用）走同一條
 * 「標記付款成功 + 履約」的路徑把訂單推進到已付款，方便本機展示整條結帳流程。
 * 需要真正端對端測試綠界通知時再用 ngrok 之類的工具把 return-url 換成能對外連線的位址。
 */
@Service
public class EcpayService {
    private static final Logger log = LoggerFactory.getLogger(EcpayService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TRADE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final DateTimeFormatter SIM_TRADE_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String PROVIDER = "ECPAY";
    private static final String PROVIDER_SIMULATE = "SIMULATE";

    private final String merchantId;
    private final String hashKey;
    private final String hashIv;
    private final String actionUrl;
    private final String returnUrl;
    private final String clientBackUrl;
    private final boolean simulateEnabled;

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderStatusHistoryRepository orderStatusHistories;
    private final PaymentTransactionRepository paymentTransactions;
    private final PaymentWebhookRepository paymentWebhooks;
    private final OrderService orderService;

    public EcpayService(
            @Value("${app.ecpay.merchant-id}") String merchantId,
            @Value("${app.ecpay.hash-key}") String hashKey,
            @Value("${app.ecpay.hash-iv}") String hashIv,
            @Value("${app.ecpay.action-url}") String actionUrl,
            @Value("${app.ecpay.return-url}") String returnUrl,
            @Value("${app.ecpay.client-back-url}") String clientBackUrl,
            @Value("${app.payment.simulate-enabled:true}") boolean simulateEnabled,
            OrderRepository orders, OrderItemRepository orderItems,
            OrderStatusHistoryRepository orderStatusHistories,
            PaymentTransactionRepository paymentTransactions, PaymentWebhookRepository paymentWebhooks,
            OrderService orderService) {
        this.merchantId = merchantId;
        this.hashKey = hashKey;
        this.hashIv = hashIv;
        this.actionUrl = actionUrl;
        this.returnUrl = returnUrl;
        this.clientBackUrl = clientBackUrl;
        this.simulateEnabled = simulateEnabled;
        this.orders = orders;
        this.orderItems = orderItems;
        this.orderStatusHistories = orderStatusHistories;
        this.paymentTransactions = paymentTransactions;
        this.paymentWebhooks = paymentWebhooks;
        this.orderService = orderService;
    }

    /** 組出 AioCheckOut/V2 表單欄位（含 CheckMacValue）；ActionUrl 是額外附加的目的地資訊，簽章算完才加，不影響 CheckMacValue */
    public Map<String, String> buildCheckoutForm(Order order) {
        String itemName = orderItems.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(OrderItem::getProductNameAtBuy)
                .reduce((a, b) -> a + "#" + b)
                .orElse("訂單" + order.getOrderNo());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("MerchantID", merchantId);
        params.put("MerchantTradeNo", order.getMerchantTradeNo());
        params.put("MerchantTradeDate", LocalDateTime.now(TAIPEI).format(TRADE_DATE_FORMAT));
        params.put("PaymentType", "aio");
        params.put("TotalAmount",
                order.getTotalAmount().setScale(0, RoundingMode.HALF_UP).toBigInteger().toString());
        params.put("TradeDesc", "訂單" + order.getOrderNo());
        params.put("ItemName", itemName);
        params.put("ReturnURL", returnUrl);
        params.put("ClientBackURL", clientBackUrl);
        params.put("ChoosePayment", "Credit");
        params.put("EncryptType", "1");

        params.put("CheckMacValue", generateCheckMacValue(params));
        params.put("ActionUrl", actionUrl);
        return params;
    }

    /** 驗證綠界通知的簽章是否正確 */
    public boolean verifyCheckMacValue(Map<String, String> params) {
        String received = params.get("CheckMacValue");
        if (received == null) {
            return false;
        }
        Map<String, String> withoutMac = new LinkedHashMap<>(params);
        withoutMac.remove("CheckMacValue");
        return generateCheckMacValue(withoutMac).equalsIgnoreCase(received);
    }

    /**
     * 處理綠界付款結果通知：驗簽 → 寫入 PaymentWebhook（以 provider+eventKey 冪等，
     * 重複的通知只記錄一次、不重複履約）→ 依 RtnCode 更新 Order／PaymentTransaction，
     * 成功時觸發 {@link OrderService#fulfillPaidOrder}。
     *
     * <p>付款成功的通知會多兩道防線才真正標記付款：
     * <ul>
     *   <li>通知金額必須等於訂單總額，不符只記錄 ERROR、不履約；</li>
     *   <li>訂單必須仍是 UNPAID——若已被逾時排程取消（見
     *       {@link OrderService#expireStaleUnpaidOrders}），代表「錢收了但訂單已取消」，
     *       記為 ERROR 並 {@code log.error} 當作告警，交由人工處理退款，不在這裡自動履約。</li>
     * </ul>
     */
    @Transactional
    public void handleNotify(Map<String, String> params) {
        boolean valid = verifyCheckMacValue(params);
        String merchantTradeNo = params.get("MerchantTradeNo");
        String tradeNo = params.get("TradeNo");
        String rtnCode = params.get("RtnCode");
        String eventKey = merchantTradeNo + "-" + rtnCode;

        if (paymentWebhooks.existsByProviderAndEventKey(PROVIDER, eventKey)) {
            log.info("重複的 ECPay 通知，略過：{}", eventKey);
            return;
        }

        PaymentWebhook webhook = paymentWebhooks.save(
                new PaymentWebhook(PROVIDER, eventKey, merchantTradeNo, tradeNo, params.toString(), valid));

        if (!valid) {
            markWebhookError(webhook, "CheckMacValue 驗證失敗");
            log.warn("ECPay 通知簽章驗證失敗：{}", merchantTradeNo);
            return;
        }

        Order order = orders.findByMerchantTradeNo(merchantTradeNo).orElse(null);
        if (order == null) {
            markWebhookError(webhook, "找不到對應的訂單：" + merchantTradeNo);
            log.warn("ECPay 通知找不到對應訂單：{}", merchantTradeNo);
            return;
        }

        PaymentTransaction payment = paymentTransactions.findByMerchantTradeNo(merchantTradeNo)
                .orElseGet(() -> paymentTransactions.save(new PaymentTransaction(
                        order, PROVIDER, merchantTradeNo, order.getTotalAmount(), order.getCurrency())));
        payment.setTradeNo(tradeNo);
        payment.setPaymentMethod(params.get("PaymentType"));
        payment.setRtnCode(parseIntOrNull(rtnCode));
        payment.setRtnMsg(params.get("RtnMsg"));
        payment.setResponsePayload(params.toString());

        String fromStatus = order.getStatus().name();
        if ("1".equals(rtnCode)) {
            BigDecimal notifiedAmount = parseAmountOrNull(params);
            if (notifiedAmount == null || notifiedAmount.compareTo(orderAmountAsInteger(order)) != 0) {
                markWebhookError(webhook, "通知金額與訂單不符：通知=" + notifiedAmount
                        + "，訂單=" + orderAmountAsInteger(order));
                log.error("ECPay 通知金額與訂單不符，未履約：mtn={} 通知={} 訂單={}",
                        merchantTradeNo, notifiedAmount, orderAmountAsInteger(order));
                return;
            }
            if (order.getStatus() != OrderStatus.UNPAID) {
                markWebhookError(webhook, "付款成功通知抵達時訂單已為 " + order.getStatus()
                        + "（可能已逾時取消），未自動履約");
                log.error("ECPay 付款成功通知抵達時訂單已非 UNPAID，需人工確認退款：mtn={} 目前狀態={}",
                        merchantTradeNo, order.getStatus());
                return;
            }
            applyPaymentSuccess(order, payment, params.get("PaymentType"), tradeNo);
        } else {
            order.markFailed();
            payment.setStatus("FAILED");
        }
        orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(),
                order.getMember(), "SYSTEM", "ECPAY_NOTIFY"));

        webhook.setProcessingStatus("PROCESSED");
        webhook.setProcessedAt(LocalDateTime.now(TAIPEI));
    }

    /**
     * ADMIN 專用的「模擬付款成功」：走與 {@link #handleNotify} 成功分支完全相同的
     * {@link #applyPaymentSuccess}（{@code markPaid} + {@link OrderService#fulfillPaidOrder}），
     * 只是免去綠界的簽章與 server-to-server 通知。本機沒有對外 URL、綠界連不進來時，
     * 用這個把訂單推進到已付款以展示整條流程。
     *
     * <p>受 {@code app.payment.simulate-enabled} 開關保護（預設開，正式環境務必設 false），
     * 端點本身也落在 {@code /api/admin/**} → 需 ADMIN。只能對 UNPAID 訂單使用。
     */
    @Transactional
    public void simulatePaymentSuccess(Integer orderId) {
        if (!simulateEnabled) {
            throw new BusinessRuleException("模擬付款功能未啟用（正式環境不提供）");
        }
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        if (order.getStatus() != OrderStatus.UNPAID) {
            throw new BusinessRuleException("只有未付款的訂單可以模擬付款");
        }

        String fromStatus = order.getStatus().name();
        String simTradeNo = "SIM" + LocalDateTime.now(TAIPEI).format(SIM_TRADE_NO_FORMAT);

        PaymentTransaction payment = paymentTransactions.findByMerchantTradeNo(order.getMerchantTradeNo())
                .orElseGet(() -> paymentTransactions.save(new PaymentTransaction(order, PROVIDER_SIMULATE,
                        order.getMerchantTradeNo(), order.getTotalAmount(), order.getCurrency())));
        payment.setTradeNo(simTradeNo);
        payment.setPaymentMethod("Simulated");
        payment.setRtnCode(1);
        payment.setRtnMsg("模擬付款成功");
        payment.setSimulatePaid(true);
        payment.setResponsePayload("{\"simulated\":true}");

        applyPaymentSuccess(order, payment, "Simulated", simTradeNo);

        orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(),
                order.getMember(), "ADMIN", "SIMULATE_PAYMENT"));

        // 交易帳留一筆，跟真實流程一致，方便後台「交易紀錄」頁對得起來
        String eventKey = order.getMerchantTradeNo() + "-SIM";
        if (!paymentWebhooks.existsByProviderAndEventKey(PROVIDER_SIMULATE, eventKey)) {
            PaymentWebhook webhook = paymentWebhooks.save(new PaymentWebhook(PROVIDER_SIMULATE, eventKey,
                    order.getMerchantTradeNo(), simTradeNo, "{\"simulated\":true}", true));
            webhook.setProcessingStatus("PROCESSED");
            webhook.setProcessedAt(LocalDateTime.now(TAIPEI));
        }
    }

    /** 標記付款成功並履約——{@link #handleNotify} 成功分支與 {@link #simulatePaymentSuccess} 共用 */
    private void applyPaymentSuccess(Order order, PaymentTransaction payment, String paymentMethod, String tradeNo) {
        order.markPaid(paymentMethod, tradeNo);
        payment.setStatus("SUCCESS");
        payment.setPaidAt(LocalDateTime.now(TAIPEI));
        orderService.fulfillPaidOrder(order);
    }

    private void markWebhookError(PaymentWebhook webhook, String message) {
        webhook.setProcessingStatus("ERROR");
        webhook.setErrorMessage(message);
        webhook.setProcessedAt(LocalDateTime.now(TAIPEI));
    }

    /** 訂單總額四捨五入為整數（結帳表單送出的 TotalAmount 就是整數字串），供與通知金額比對 */
    private BigDecimal orderAmountAsInteger(Order order) {
        return order.getTotalAmount().setScale(0, RoundingMode.HALF_UP);
    }

    /** 綠界通知的金額欄位：AioCheckOut 回傳用 TradeAmt，少數情境用 TotalAmount，兩個都看 */
    private BigDecimal parseAmountOrNull(Map<String, String> params) {
        String raw = params.get("TradeAmt");
        if (raw == null || raw.isBlank()) {
            raw = params.get("TotalAmount");
        }
        try {
            return raw == null || raw.isBlank() ? null : new BigDecimal(raw.trim()).setScale(0, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseIntOrNull(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 綠界 CheckMacValue 演算法：排序參數 → 前後包 HashKey/HashIV → URL encode 轉小寫並還原特定字元 → SHA256 → 轉大寫 */
    private String generateCheckMacValue(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder raw = new StringBuilder("HashKey=").append(hashKey);
        for (var entry : sorted.entrySet()) {
            raw.append('&').append(entry.getKey()).append('=').append(entry.getValue());
        }
        raw.append("&HashIV=").append(hashIv);

        return sha256(urlEncode(raw.toString())).toUpperCase();
    }

    /** 依綠界規定：application/x-www-form-urlencoded 編碼、轉小寫，再還原 .NET UrlEncode 不會跳脫的符號 */
    private String urlEncode(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8).toLowerCase();
        return encoded
                .replace("%2d", "-")
                .replace("%5f", "_")
                .replace("%2e", ".")
                .replace("%21", "!")
                .replace("%2a", "*")
                .replace("%28", "(")
                .replace("%29", ")");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
