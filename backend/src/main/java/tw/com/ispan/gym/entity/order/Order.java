package tw.com.ispan.gym.entity.order;

import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;

import tw.com.ispan.gym.enums.order.InvoiceType;
import tw.com.ispan.gym.enums.order.OrderStatus;

import tw.com.ispan.gym.common.persistence.TaipeiTimestampEntity;
import tw.com.ispan.gym.exception.BusinessRuleException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "orders", uniqueConstraints = {
        @UniqueConstraint(name = "UQ_orders_mtn", columnNames = "merchant_trade_no"),
        @UniqueConstraint(name = "UX_orders_order_no", columnNames = "order_no") })
public class Order extends TaipeiTimestampEntity {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id", nullable = false)
    private Integer orderId;

    public Integer getOrderId() {
        return orderId;
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, foreignKey = @ForeignKey(name = "FK_orders_member"))
    private Member member;
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;
    @Column(name = "payment_method", length = 20)
    private String paymentMethod;
    /**
     * 結帳當下使用者選擇的付款方式（Credit／ATM／CVS），跟 {@link #paymentMethod} 分開存放——
     * 後者要留給「金流分布報表」（見 OrderRepository#aggregateByPaymentMethod）只統計真正走過
     * 付款流程的訂單；若拿「當初選了什麼」去覆蓋，會讓還沒真正付款的訂單也被算進報表裡失真。
     */
    @Column(name = "intended_payment_method", length = 20)
    private String intendedPaymentMethod;
    /** 綠界對 MerchantTradeNo 的長度上限；超過會直接交易失敗（訊息代碼 1000500） */
    public static final int MAX_MERCHANT_TRADE_NO_LENGTH = 20;

    /**
     * 送給綠界的交易編號，長度受 {@link #MAX_MERCHANT_TRADE_NO_LENGTH} 限制。
     * 建單時的基底編號固定 18 碼，末端 2 碼留給重試後綴，見 {@link #renewMerchantTradeNo}。
     */
    @Column(name = "merchant_trade_no", nullable = false, length = MAX_MERCHANT_TRADE_NO_LENGTH)
    private String merchantTradeNo;

    /** 已送出過幾次綠界結帳表單；每次換號時遞增，用來組出不重複的後綴 */
    @Column(name = "checkout_attempt", nullable = false)
    private Integer checkoutAttempt = 0;
    @Column(name = "trade_no", length = 20)
    private String tradeNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OrderStatus status;
    @Column(name = "paid_at")
    private LocalDateTime paidAt;
    @Nationalized
    @Column(name = "receiver_name", length = 50)
    private String receiverName;
    @Column(name = "receiver_phone", length = 20)
    private String receiverPhone;
    @Nationalized
    @Column(name = "receiver_address", length = 200)
    private String receiverAddress;
    /** 本筆訂單消耗掉的那一張已領取優惠券（券種可由 memberCoupon.coupon 取得） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_coupon_id", foreignKey = @ForeignKey(name = "FK_orders_member_coupon"))
    private MemberCoupon memberCoupon;
    /**
     * 有值代表這是一筆訂閱方案購買。訂閱不像商品/課程走 order_items 多型
     * （product_id ⊕ course_id 的 CK_oi_exactly_one_ref 互斥），直接在 Order 上標記，
     * 履約（建立訂閱）待付款成功後才執行，見 OrderService#fulfillPaidOrder。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_plan_id", foreignKey = @ForeignKey(name = "FK_orders_subscription_plan"))
    private SubscriptionPlan subscriptionPlan;
    @Column(name = "original_amount", precision = 10, scale = 2)
    private BigDecimal originalAmount;
    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount;
    @Column(name = "order_no", nullable = false, length = 30)
    private String orderNo;
    @Column(name = "payment_status", nullable = false, length = 20)
    private String paymentStatus;
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
    @Column(name = "subtotal_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotalAmount;
    @Column(name = "receiver_email", length = 100)
    private String receiverEmail;
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
    @Nationalized
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    /**
     * 發票開立方式與所需資料，結帳時由使用者選填，目前僅儲存與顯示（未串綠界電子發票開立）。
     * 全部可為 null：舊訂單、或使用者選「個人（會員載具）」時 carrier/taxId/donationCode 皆空。
     * 格式驗證見 OrderService#assertInvoiceValid。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", length = 20)
    private InvoiceType invoiceType;
    @Column(name = "invoice_carrier", length = 64)
    private String invoiceCarrier;
    @Column(name = "invoice_tax_id", length = 8)
    private String invoiceTaxId;
    @Column(name = "invoice_donation_code", length = 10)
    private String invoiceDonationCode;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    protected Order() {
    }

    public Order(Member member, String merchantTradeNo, String orderNo, BigDecimal originalAmount,
            BigDecimal discountAmount, BigDecimal subtotalAmount, BigDecimal totalAmount,
            MemberCoupon memberCoupon, String receiverName, String receiverPhone, String receiverAddress,
            String receiverEmail) {
        this.member = member;
        this.merchantTradeNo = merchantTradeNo;
        this.orderNo = orderNo;
        this.originalAmount = originalAmount;
        this.discountAmount = discountAmount;
        this.subtotalAmount = subtotalAmount;
        this.totalAmount = totalAmount;
        this.memberCoupon = memberCoupon;
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.receiverAddress = receiverAddress;
        this.receiverEmail = receiverEmail;
        this.status = OrderStatus.UNPAID;
        this.paymentStatus = OrderStatus.UNPAID.name();
        this.currency = "TWD";
    }

    /**
     * 目前只允許取消未付款的訂單；已付款後的取消需搭配退款流程，待金流串接後再開放。
     * payment_status 不隨之變動——這筆訂單從未發生過金流事件，維持 UNPAID 即可；
     * CANCELLED 不是資料庫 CK_orders_payment_status 允許的值。
     */
    public void cancel(String reason) {
        if (status != OrderStatus.UNPAID) {
            throw new BusinessRuleException("只有未付款的訂單可以取消");
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now(TAIPEI);
        this.cancelReason = reason;
    }

    /**
     * 結帳當下記錄使用者選擇的付款方式，只在 UNPAID 時才有意義（付款完成後應以
     * {@link #paymentMethod} 上綠界回傳的實際值為準，不該再被這裡覆蓋）。
     */
    public void recordIntendedPaymentMethod(String method) {
        if (status == OrderStatus.UNPAID) {
            this.intendedPaymentMethod = method;
        }
    }

    /**
     * 換一組新的綠界交易編號。
     *
     * <p>綠界要求 MerchantTradeNo 每次送出結帳都必須是全新的，同一組送第二次會被回
     * 「訂單編號重覆」。使用者在綠界付款頁中途離開後回來重新付款，就是同一筆訂單要
     * 再次送出，因此每次組表單前都得換號（見 EcpayService#buildCheckoutForm）。
     *
     * <p>後綴而不是整組重產，是為了讓綠界對舊編號送來的遲到通知仍能經由
     * {@code LIKE 原編號%} 追溯回這筆訂單，也方便一眼看出是第幾次嘗試。
     *
     * <p>只有 UNPAID 能換號：已付款的訂單換掉編號會讓既有的付款紀錄對不回來。
     *
     * <p>第一次送出（attempt 0 → 1）就會加上 {@code R1} 後綴，不保留無後綴的原編號——
     * 這樣「原編號」永遠只是共同前綴、不會有任何一次結帳真的用它，避免第二次換號時
     * 撞回第一次用過的編號。
     *
     * <p>後綴固定 2 碼（{@code R} + 36 進位的一碼），因為綠界的 20 碼上限只讓基底編號留下這麼多空間。
     * 第 36 次以後會繞回 {@code R0}，實務上同一筆訂單不會重試這麼多次；真的繞回也只是撞到
     * 很久以前那次已被放棄的編號，不影響當下這次結帳。
     *
     * <p>基底一律先裁到 18 碼再接後綴：舊格式（14 碼完整年份）產生的編號本身就用滿 20 碼，
     * 直接接後綴會超過上限而寫不進資料庫。裁切只影響這些還沒付款的舊訂單，且裁掉的是亂數尾段，
     * 換號後仍由唯一約束把關不重複。
     */
    public void renewMerchantTradeNo() {
        if (status != OrderStatus.UNPAID) {
            return;
        }
        this.checkoutAttempt = (checkoutAttempt == null ? 0 : checkoutAttempt) + 1;
        String base = baseMerchantTradeNo();
        if (base.length() > MAX_MERCHANT_TRADE_NO_LENGTH - 2) {
            base = base.substring(0, MAX_MERCHANT_TRADE_NO_LENGTH - 2);
        }
        this.merchantTradeNo = base
                + "R" + Character.toUpperCase(Character.forDigit(checkoutAttempt % 36, 36));
    }

    /** 去掉重試後綴的原始編號；綠界遲到通知要靠它追溯回這筆訂單 */
    public String baseMerchantTradeNo() {
        int marker = merchantTradeNo.lastIndexOf('R');
        return marker < 0 ? merchantTradeNo : merchantTradeNo.substring(0, marker);
    }

    /** ECPay 通知付款成功：UNPAID → PAID。paymentStatus 維持既有值不動，理由同 {@link #cancel} */
    public void markPaid(String paymentMethod, String tradeNo) {
        if (status != OrderStatus.UNPAID) {
            throw new BusinessRuleException("只有未付款的訂單可以標記為已付款");
        }
        this.status = OrderStatus.PAID;
        this.paymentMethod = paymentMethod;
        this.tradeNo = tradeNo;
        this.paidAt = LocalDateTime.now(TAIPEI);
    }

    /** ECPay 通知付款失敗：UNPAID → FAILED */
    public void markFailed() {
        if (status != OrderStatus.UNPAID) {
            throw new BusinessRuleException("只有未付款的訂單可以標記為付款失敗");
        }
        this.status = OrderStatus.FAILED;
    }

    /**
     * 申請退款（含退貨）前的資格檢查；已出貨/已完成的訂單視同已付款，一樣可以申請——
     * 商品出貨後客人才要求退貨是正常情境，不是只有出貨前才能反悔。
     * 通過後由 OrderService 建立 PaymentRefund（PENDING），訂單狀態維持原樣直到後台實際完成退款。
     */
    public void assertRefundable() {
        if (status != OrderStatus.PAID && status != OrderStatus.SHIPPED && status != OrderStatus.COMPLETED) {
            throw new BusinessRuleException("只有已付款、已出貨或已完成的訂單可以申請退款");
        }
    }

    /** 後台完成退款：PAID／SHIPPED／COMPLETED → REFUNDED */
    public void markRefunded() {
        if (status != OrderStatus.PAID && status != OrderStatus.SHIPPED && status != OrderStatus.COMPLETED) {
            throw new BusinessRuleException("只有已付款、已出貨或已完成的訂單可以標記為已退款");
        }
        this.status = OrderStatus.REFUNDED;
    }

    /** 後台標記已出貨：PAID → SHIPPED。僅適用於含實體商品的訂單，課程/訂閱訂單無需這一步 */
    public void markShipped() {
        if (status != OrderStatus.PAID) {
            throw new BusinessRuleException("只有已付款的訂單可以標記為已出貨");
        }
        this.status = OrderStatus.SHIPPED;
    }

    /** 後台標記訂單完成：SHIPPED → COMPLETED；課程/訂閱訂單無出貨步驟，允許直接從 PAID 完成 */
    /**
     * 標記為已完成。
     *
     * <p>
     * 含實體商品的訂單必須先出貨（PAID → SHIPPED → COMPLETED）；私教課與訂閱方案
     * 沒有實體要寄，付款後可直接完成（PAID → COMPLETED），不必經過出貨。
     *
     * <p>
     * {@code hasPhysicalProduct} 由呼叫端查明細後傳入——Order 沒有持有 orderItems
     * 關聯（明細以 order_id 反向關聯，見 OrderItem），實體本身查不到自己的品項組成。
     */
    public void markCompleted(boolean hasPhysicalProduct) {
        if (status != OrderStatus.SHIPPED && status != OrderStatus.PAID) {
            throw new BusinessRuleException("只有已出貨或已付款的訂單可以標記為已完成");
        }
        if (hasPhysicalProduct && status != OrderStatus.SHIPPED) {
            throw new BusinessRuleException("此訂單含實體商品，請先標記為已出貨再完成");
        }
        this.status = OrderStatus.COMPLETED;
    }

    public Member getMember() {
        return member;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public String getIntendedPaymentMethod() {
        return intendedPaymentMethod;
    }

    public String getMerchantTradeNo() {
        return merchantTradeNo;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public String getReceiverEmail() {
        return receiverEmail;
    }

    public MemberCoupon getMemberCoupon() {
        return memberCoupon;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getSubtotalAmount() {
        return subtotalAmount;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Integer getVersion() {
        return version;
    }

    public SubscriptionPlan getSubscriptionPlan() {
        return subscriptionPlan;
    }

    public void setSubscriptionPlan(SubscriptionPlan subscriptionPlan) {
        this.subscriptionPlan = subscriptionPlan;
    }

    /**
     * 設定發票資訊。type 為 null 時視同「個人（會員載具）」，其餘欄位一律清空；
     * 各 type 需要的欄位由 OrderService#assertInvoiceValid 先驗過格式，這裡只負責存。
     */
    public void applyInvoice(InvoiceType type, String carrier, String taxId, String donationCode) {
        this.invoiceType = type;
        this.invoiceCarrier = type == InvoiceType.MOBILE_BARCODE ? carrier : null;
        this.invoiceTaxId = type == InvoiceType.COMPANY ? taxId : null;
        this.invoiceDonationCode = type == InvoiceType.NPO_DONATION ? donationCode : null;
    }

    public InvoiceType getInvoiceType() {
        return invoiceType;
    }

    public String getInvoiceCarrier() {
        return invoiceCarrier;
    }

    public String getInvoiceTaxId() {
        return invoiceTaxId;
    }

    public String getInvoiceDonationCode() {
        return invoiceDonationCode;
    }
}
