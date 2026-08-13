package tw.com.ispan.gym.service.payment;

import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.entity.payment.PaymentWebhook;
import tw.com.ispan.gym.repository.OrderItemRepository;
import tw.com.ispan.gym.repository.OrderRepository;
import tw.com.ispan.gym.repository.PaymentTransactionRepository;
import tw.com.ispan.gym.repository.PaymentWebhookRepository;
import tw.com.ispan.gym.service.order.OrderService;

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
 * 本專案前端未串接，回傳單純的 Map（JSON）供 Postman 直接檢視/測試簽章是否正確，
 * 而不是渲染一個自動送出的 HTML 表單。
 *
 * <p>{@link #handleNotify} 處理綠界的付款結果通知（server-to-server callback）。
 * 本機開發沒有對外公開的 URL，綠界連不進來，測試時改用 Postman 依同樣的簽章演算法
 * 手動組出 payload 直接打 {@code POST /api/payments/ecpay/notify} 驗證這條路徑；
 * 之後要做真正端對端測試再用 ngrok 之類的工具把 return-url 換成能對外連線的位址。
 */
@Service
public class EcpayService {
    private static final Logger log = LoggerFactory.getLogger(EcpayService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TRADE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final String PROVIDER = "ECPAY";

    private final String merchantId;
    private final String hashKey;
    private final String hashIv;
    private final String actionUrl;
    private final String returnUrl;
    private final String clientBackUrl;

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
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
            OrderRepository orders, OrderItemRepository orderItems,
            PaymentTransactionRepository paymentTransactions, PaymentWebhookRepository paymentWebhooks,
            OrderService orderService) {
        this.merchantId = merchantId;
        this.hashKey = hashKey;
        this.hashIv = hashIv;
        this.actionUrl = actionUrl;
        this.returnUrl = returnUrl;
        this.clientBackUrl = clientBackUrl;
        this.orders = orders;
        this.orderItems = orderItems;
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
            webhook.setProcessingStatus("ERROR");
            webhook.setErrorMessage("CheckMacValue 驗證失敗");
            webhook.setProcessedAt(LocalDateTime.now(TAIPEI));
            log.warn("ECPay 通知簽章驗證失敗：{}", merchantTradeNo);
            return;
        }

        Order order = orders.findByMerchantTradeNo(merchantTradeNo).orElse(null);
        if (order == null) {
            webhook.setProcessingStatus("ERROR");
            webhook.setErrorMessage("找不到對應的訂單：" + merchantTradeNo);
            webhook.setProcessedAt(LocalDateTime.now(TAIPEI));
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

        if ("1".equals(rtnCode)) {
            order.markPaid(params.get("PaymentType"), tradeNo);
            payment.setStatus("SUCCESS");
            payment.setPaidAt(LocalDateTime.now(TAIPEI));
            orderService.fulfillPaidOrder(order);
        } else {
            order.markFailed();
            payment.setStatus("FAILED");
        }

        webhook.setProcessingStatus("PROCESSED");
        webhook.setProcessedAt(LocalDateTime.now(TAIPEI));
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
