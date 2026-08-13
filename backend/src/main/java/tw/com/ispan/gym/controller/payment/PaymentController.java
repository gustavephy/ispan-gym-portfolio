package tw.com.ispan.gym.controller.payment;

import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.payment.EcpayService;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final EcpayService ecpayService;
    private final OrderRepository orders;
    private final CurrentUserProvider currentUser;

    public PaymentController(EcpayService ecpayService, OrderRepository orders, CurrentUserProvider currentUser) {
        this.ecpayService = ecpayService;
        this.orders = orders;
        this.currentUser = currentUser;
    }

    /** 組出綠界 AioCheckOut 表單欄位；只有訂單所屬會員本人可以查詢 */
    @PostMapping("/ecpay/checkout/{orderId}")
    public Map<String, String> checkout(@PathVariable Long orderId) {
        Order order = orders.findByIdAndMemberId(orderId, currentUser.requireCurrentMemberId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        return ecpayService.buildCheckoutForm(order);
    }

    /**
     * 綠界 server-to-server 付款結果通知，不帶登入態（SecurityConfig 已對此路徑 permitAll）。
     * 無論處理成功與否都回應「1|OK」——回別的內容綠界會判定失敗並重送，內部真正的錯誤
     * 已經記錄在 PaymentWebhook／log 裡供後續排查，不需要靠讓綠界重試來補救。
     */
    @PostMapping(value = "/ecpay/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public String notify(@RequestParam Map<String, String> params) {
        try {
            ecpayService.handleNotify(params);
        } catch (Exception ex) {
            log.error("處理 ECPay 通知時發生例外：{}", params, ex);
        }
        return "1|OK";
    }
}
