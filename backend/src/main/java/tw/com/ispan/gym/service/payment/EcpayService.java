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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

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

    /** 支援的付款方式；購物車一般商品結帳可三選一，訂閱／私教課直接結帳固定用 Credit（見 OrderController） */
    private static final java.util.Set<String> SUPPORTED_PAYMENT_METHODS = java.util.Set.of("Credit", "ATM", "CVS");
    /** 綠界對 ItemName 的長度上限；超過會被對方截斷而導致 CheckMacValue 不一致 */
    private static final int MAX_ITEM_NAME_LENGTH = 400;
    /** ATM 虛擬帳號有效期限（天），綠界允許 1-60 天，此處用官方文件建議的預設值 */
    private static final String ATM_EXPIRE_DAYS = "3";
    /** 超商代碼有效期限（分鐘），綠界預設 10080 分鐘＝7 天 */
    private static final String CVS_STORE_EXPIRE_MINUTES = "10080";
    /** 超商代碼繳費金額限制（含手續費），低於/高於此範圍綠界會直接拒絕 AioCheckOut 請求 */
    private static final BigDecimal CVS_MIN_AMOUNT = BigDecimal.valueOf(27);
    private static final BigDecimal CVS_MAX_AMOUNT = BigDecimal.valueOf(20000);

    private final String merchantId;
    private final String hashKey;
    private final String hashIv;
    private final String actionUrl;
    private final String returnUrl;
    private final String paymentInfoUrl;
    private final String queryTradeInfoUrl;
    private final String clientBackUrl;
    private final boolean simulateEnabled;

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderStatusHistoryRepository orderStatusHistories;
    private final PaymentTransactionRepository paymentTransactions;
    private final PaymentWebhookRepository paymentWebhooks;
    private final OrderService orderService;
    private final RestTemplate restTemplate;

    public EcpayService(
            @Value("${app.ecpay.merchant-id}") String merchantId,
            @Value("${app.ecpay.hash-key}") String hashKey,
            @Value("${app.ecpay.hash-iv}") String hashIv,
            @Value("${app.ecpay.action-url}") String actionUrl,
            @Value("${app.ecpay.return-url}") String returnUrl,
            @Value("${app.ecpay.payment-info-url}") String paymentInfoUrl,
            @Value("${app.ecpay.query-trade-info-url}") String queryTradeInfoUrl,
            @Value("${app.ecpay.client-back-url}") String clientBackUrl,
            @Value("${app.payment.simulate-enabled:true}") boolean simulateEnabled,
            OrderRepository orders, OrderItemRepository orderItems,
            OrderStatusHistoryRepository orderStatusHistories,
            PaymentTransactionRepository paymentTransactions, PaymentWebhookRepository paymentWebhooks,
            OrderService orderService, RestTemplate restTemplate) {
        this.merchantId = merchantId;
        this.hashKey = hashKey;
        this.hashIv = hashIv;
        this.actionUrl = actionUrl;
        this.returnUrl = returnUrl;
        this.paymentInfoUrl = paymentInfoUrl;
        this.queryTradeInfoUrl = queryTradeInfoUrl;
        this.clientBackUrl = clientBackUrl;
        this.simulateEnabled = simulateEnabled;
        this.orders = orders;
        this.orderItems = orderItems;
        this.orderStatusHistories = orderStatusHistories;
        this.paymentTransactions = paymentTransactions;
        this.paymentWebhooks = paymentWebhooks;
        this.orderService = orderService;
        this.restTemplate = restTemplate;
    }

    /** 購物車一般商品結帳三選一；訂閱／私教課直接結帳固定用 Credit，見 {@link #buildCheckoutForm(Order)} */
    public Map<String, String> buildCheckoutForm(Order order) {
        return buildCheckoutForm(order, "Credit");
    }

    /**
     * 組出 AioCheckOut/V2 表單欄位（含 CheckMacValue）；ActionUrl 是額外附加的目的地資訊，簽章算完才加，不影響 CheckMacValue。
     *
     * <p>ATM／CVS 是「非即時」付款：消費者送出表單後，綠界先回一次取號通知到 {@link #paymentInfoUrl}
     * （見 {@link #handlePaymentInfo}，帶回虛擬帳號／超商代碼），消費者實際完成轉帳/繳費後才會再有一次
     * 付款結果通知打到 {@link #returnUrl}（{@link #handleNotify}，RtnCode=1 才算入帳）——兩者是不同的
     * callback URL，跟 Credit 只有一次 {@link #returnUrl} 通知不同，{@link #handleNotify} 不需要因此改動。
     *
     * @param choosePayment {@code Credit}／{@code ATM}／{@code CVS}，見 {@link #SUPPORTED_PAYMENT_METHODS}
     */
    @Transactional
    public Map<String, String> buildCheckoutForm(Order order, String choosePayment) {
        // 只有待付款訂單能組付款表單。少了這道檢查時，已付款訂單的 renewMerchantTradeNo()
        // 會靜默略過換號、把已付清的舊編號原樣送去綠界，使用者（畫面停在舊狀態、按下「前往付款」）
        // 會看到綠界的「訂單編號重覆」交易失敗頁——實際發生過：付款與返回幾乎同秒，
        // 返回當下的自動查詢還查不到入帳、畫面維持待付款，幾秒後訂單已翻成已付款。
        if (order.getStatus() != OrderStatus.UNPAID) {
            throw new BusinessRuleException("這筆訂單不是待付款狀態，不需要再付款；請重新整理訂單列表確認最新狀態");
        }
        if (!SUPPORTED_PAYMENT_METHODS.contains(choosePayment)) {
            throw new BusinessRuleException("不支援的付款方式：" + choosePayment);
        }
        if ("CVS".equals(choosePayment)) {
            BigDecimal amount = order.getTotalAmount();
            if (amount.compareTo(CVS_MIN_AMOUNT) < 0 || amount.compareTo(CVS_MAX_AMOUNT) > 0) {
                throw new BusinessRuleException("超商代碼繳費金額須介於 NT$" + CVS_MIN_AMOUNT
                        + " 至 NT$" + CVS_MAX_AMOUNT + " 之間，請改用其他付款方式");
            }
        }
        // 記下使用者這次選的付款方式，供後台「模擬付款成功」判斷是否要擋下 ATM/CVS（見 simulatePaymentSuccess）
        order.recordIntendedPaymentMethod(choosePayment);
        // 綠界的 MerchantTradeNo 每次送出結帳都必須是全新的，沿用同一組會被回「訂單編號重覆」。
        // 使用者在付款頁中途離開後回來重新付款就是這個情形，因此每次組表單前都換一組新號。
        order.renewMerchantTradeNo();

        String itemName = truncateItemName(orderItems.findAllByOrderIdOrderByIdAsc(order.getOrderId()).stream()
                .map(OrderItem::getProductNameAtBuy)
                .reduce((a, b) -> a + "#" + b)
                .orElse("訂單" + order.getOrderNo()));

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
        params.put("ChoosePayment", choosePayment);
        params.put("EncryptType", "1");
        if ("ATM".equals(choosePayment)) {
            params.put("ExpireDate", ATM_EXPIRE_DAYS);
            params.put("PaymentInfoURL", paymentInfoUrl);
        } else if ("CVS".equals(choosePayment)) {
            params.put("StoreExpireDate", CVS_STORE_EXPIRE_MINUTES);
            params.put("PaymentInfoURL", paymentInfoUrl);
        }

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

        Order order = resolveOrder(merchantTradeNo);
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
     * ATM／CVS 取號結果通知：綠界產生虛擬帳號／超商代碼後打到 {@link #paymentInfoUrl}，
     * 跟最終付款結果通知（{@link #handleNotify} 打到 {@link #returnUrl}）是不同的 callback，
     * 不會互相影響——這裡只負責把帳號/代碼記下來給前端顯示，訂單狀態仍維持 UNPAID，
     * 真正入帳要等 {@link #handleNotify} 收到 RtnCode=1。
     *
     * <p>eventKey 用 "-INFO" 字尾跟 {@link #handleNotify} 的 "mtn-rtnCode" 區隔，避免同一筆訂單的
     * 取號通知跟付款結果通知被誤判成重複而互相蓋掉。
     */
    @Transactional
    public void handlePaymentInfo(Map<String, String> params) {
        boolean valid = verifyCheckMacValue(params);
        String merchantTradeNo = params.get("MerchantTradeNo");
        String eventKey = merchantTradeNo + "-INFO";

        if (paymentWebhooks.existsByProviderAndEventKey(PROVIDER, eventKey)) {
            log.info("重複的 ECPay 取號通知，略過：{}", eventKey);
            return;
        }

        PaymentWebhook webhook = paymentWebhooks.save(new PaymentWebhook(
                PROVIDER, eventKey, merchantTradeNo, params.get("TradeNo"), params.toString(), valid));

        if (!valid) {
            markWebhookError(webhook, "CheckMacValue 驗證失敗");
            log.warn("ECPay 取號通知簽章驗證失敗：{}", merchantTradeNo);
            return;
        }

        Order order = resolveOrder(merchantTradeNo);
        if (order == null) {
            markWebhookError(webhook, "找不到對應的訂單：" + merchantTradeNo);
            log.warn("ECPay 取號通知找不到對應訂單：{}", merchantTradeNo);
            return;
        }

        PaymentTransaction payment = paymentTransactions.findByMerchantTradeNo(merchantTradeNo)
                .orElseGet(() -> paymentTransactions.save(new PaymentTransaction(
                        order, PROVIDER, merchantTradeNo, order.getTotalAmount(), order.getCurrency())));
        String paymentType = params.get("PaymentType");
        payment.setPaymentType(paymentType);
        payment.setPaymentExpireAt(parseExpireDateOrNull(params.get("ExpireDate")));
        if (paymentType != null && paymentType.startsWith("ATM_")) {
            payment.setBankCode(params.get("BankCode"));
            payment.setVAccount(params.get("vAccount"));
        } else if (paymentType != null && paymentType.startsWith("CVS_")) {
            payment.setPaymentNo(params.get("PaymentNo"));
        }

        webhook.setProcessingStatus("PROCESSED");
        webhook.setProcessedAt(LocalDateTime.now(TAIPEI));
    }

    /**
     * 把 ItemName 截到綠界的 400 字元上限內。
     *
     * <p>超過的話綠界會自行截斷，而截斷點若落在 UTF-8 多位元組字元中間會產生亂碼，
     * 使綠界端算出的 CheckMacValue 與我方不一致而掉單。自己先截就不會有這個問題——
     * 明細品項多或商品名稱長的訂單（以 # 串接）就可能超過。
     */
    private String truncateItemName(String itemName) {
        if (itemName == null || itemName.length() <= MAX_ITEM_NAME_LENGTH) {
            return itemName;
        }
        return itemName.substring(0, MAX_ITEM_NAME_LENGTH);
    }

    /**
     * 由通知帶回的 MerchantTradeNo 找出訂單。
     *
     * <p>先精準比對；查不到再去掉重試後綴用前綴找一次——使用者中途放棄付款後重新結帳會換號
     * （見 {@link Order#renewMerchantTradeNo}），綠界對舊編號送來的遲到通知只能靠前綴回推。
     * 前綴本身是建單時產生、有唯一約束把關的 20 碼編號，不會誤中其他訂單。
     */
    private Order resolveOrder(String merchantTradeNo) {
        Order exact = orders.findByMerchantTradeNo(merchantTradeNo).orElse(null);
        if (exact != null) {
            return exact;
        }
        int marker = merchantTradeNo.lastIndexOf('R');
        if (marker < 0) {
            return null;
        }
        return orders.findByMerchantTradeNoStartingWith(merchantTradeNo.substring(0, marker)).orElse(null);
    }

    /** 綠界的 ExpireDate 格式跟 MerchantTradeDate 相同（yyyy/MM/dd HH:mm:ss），解析失敗就不顯示期限，不擋主流程 */
    private LocalDateTime parseExpireDateOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, TRADE_DATE_FORMAT);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    /**
     * 主動向綠界查詢這筆訂單的真實付款結果（QueryTradeInfo/V5），取代等待 {@link #handleNotify}／
     * {@link #handlePaymentInfo} 主動通知——本機沒有對外 URL 收不到綠界的呼叫，改成我方主動打這支
     * 官方查詢 API 問結果，不需要任何對外網址。查到「已付款」時走跟 {@link #handleNotify} 相同的
     * {@link #applyPaymentSuccess} 履約路徑。
     *
     * <p>綠界官方文件明講這支查詢 API 有頻率限制、不建議拿來做輪詢，只建議在使用者主動觸發時
     * （例如剛從綠界頁面返回、或在「我的訂單」按查詢按鈕）呼叫一次，見 OrderController 的
     * rate limit 防護，不會自動重試。
     *
     * <p>只有回傳 {@code TradeStatus=1} 才視為付款成功；其餘值（含「0」未付款）一律當作
     * 「還沒有確定結果」而不動訂單狀態——主動查詢查到未付款不代表交易失敗，很可能只是使用者
     * 還沒完成 ATM/超商繳費，若比照 {@link #handleNotify} 把它標記失敗，會讓使用者之後繳了費
     * 也無法再走正常流程。
     *
     * @return 查詢後的訂單狀態（可能跟呼叫前相同，代表沒有查到新結果）
     */
    @Transactional
    public OrderStatus syncPaymentStatus(Order order) {
        if (order.getStatus() != OrderStatus.UNPAID) {
            return order.getStatus(); // 已經有結果（先前的通知或模擬付款已經推進過），不必再查
        }

        Map<String, String> query = new LinkedHashMap<>();
        query.put("MerchantID", merchantId);
        query.put("MerchantTradeNo", order.getMerchantTradeNo());
        query.put("TimeStamp", String.valueOf(Instant.now().getEpochSecond()));
        query.put("CheckMacValue", generateCheckMacValue(query));

        Map<String, String> result = callQueryTradeInfo(query);
        if (result == null) {
            return order.getStatus(); // 查詢本身失敗（網路/逾時/綠界端錯誤），維持原狀態，前端可再試一次
        }
        // 只有回應真的帶簽章時才驗證；綠界文件沒有明確保證這支查詢 API 一定回簽章欄位，
        // 缺席時退而求其次僅靠下面的金額比對做最後防線，避免因為不確定的協定細節讓功能整個失效
        if (result.containsKey("CheckMacValue") && !verifyCheckMacValue(result)) {
            log.warn("QueryTradeInfo 回應簽章驗證失敗，略過此次查詢結果：mtn={}", order.getMerchantTradeNo());
            return order.getStatus();
        }
        if (!"1".equals(result.get("TradeStatus"))) {
            return order.getStatus(); // 尚未查到付款成功，可能還沒付或還在處理中，不視為失敗
        }

        BigDecimal queriedAmount = parseAmountOrNull(result);
        if (queriedAmount == null || queriedAmount.compareTo(orderAmountAsInteger(order)) != 0) {
            log.error("QueryTradeInfo 金額與訂單不符，未履約：mtn={} 查詢={} 訂單={}",
                    order.getMerchantTradeNo(), queriedAmount, orderAmountAsInteger(order));
            return order.getStatus();
        }

        PaymentTransaction payment = paymentTransactions.findByMerchantTradeNo(order.getMerchantTradeNo())
                .orElseGet(() -> paymentTransactions.save(new PaymentTransaction(
                        order, PROVIDER, order.getMerchantTradeNo(), order.getTotalAmount(), order.getCurrency())));
        String fromStatus = order.getStatus().name();
        applyPaymentSuccess(order, payment, result.get("PaymentType"), result.get("TradeNo"));
        orderStatusHistories.save(new OrderStatusHistory(order, fromStatus, order.getStatus().name(),
                order.getMember(), "SYSTEM", "QUERY_TRADE_INFO"));
        return order.getStatus();
    }

    /** 呼叫綠界查詢 API；網路/逾時等例外一律吞掉回 null，查詢失敗不該讓使用者的請求整個炸掉 */
    private Map<String, String> callQueryTradeInfo(Map<String, String> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        params.forEach(body::add);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    queryTradeInfoUrl, new HttpEntity<>(body, headers), String.class);
            return parseFormEncoded(response.getBody());
        } catch (RestClientException ex) {
            log.warn("查詢 ECPay 訂單狀態失敗（網路或逾時）：mtn={}", params.get("MerchantTradeNo"), ex);
            return null;
        }
    }

    /** 綠界查詢 API 回應是 URL-encoded 字串（key1=value1&key2=value2...），不是 JSON，需自行解析 */
    private Map<String, String> parseFormEncoded(String body) {
        Map<String, String> result = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return result;
        }
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    /**
     * ADMIN 專用的「模擬付款成功」：走與 {@link #handleNotify} 成功分支完全相同的
     * {@link #applyPaymentSuccess}（{@code markPaid} + {@link OrderService#fulfillPaidOrder}），
     * 只是免去綠界的簽章與 server-to-server 通知。本機沒有對外 URL、綠界連不進來時，
     * 用這個把訂單推進到已付款以展示整條流程。
     *
     * <p>受 {@code app.payment.simulate-enabled} 開關保護（預設開，正式環境務必設 false），
     * 端點本身也落在 {@code /api/admin/**} → 需 ADMIN。只能對 UNPAID 訂單使用。
     *
     * <p>ATM／超商代碼訂單不提供模擬付款——這兩種是使用者要離開瀏覽器另外完成的非即時付款，
     * 「模擬」它們完成沒有實際展示意義，且會跟前台真正的 {@link #syncPaymentStatus} 查詢流程
     * 混淆；信用卡才是本機沒有對外 URL 時真正需要這個後門展示履約流程的情境。
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
        if ("ATM".equals(order.getIntendedPaymentMethod()) || "CVS".equals(order.getIntendedPaymentMethod())) {
            throw new BusinessRuleException("此訂單選擇 ATM／超商代碼繳費，不提供模擬付款，請等待實際繳費或使用查詢付款結果功能");
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
