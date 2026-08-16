package tw.com.ispan.gym.dto.order;

import java.util.Map;

/**
 * 訂閱一步到位結帳的回應：{@link #order} 是剛建立的未付款訂閱訂單，
 * {@link #ecpayForm} 是 {@code EcpayService#buildCheckoutForm} 組好的 ECPay AioCheckOut 表單欄位
 * （含 CheckMacValue 與 ActionUrl），前端收到後直接組表單送出即可導向 ECPay 付款頁。
 */
public record SubscriptionCheckoutResponse(
        OrderDetailResponse order,
        Map<String, String> ecpayForm) {}
