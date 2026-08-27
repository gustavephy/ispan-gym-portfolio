package tw.com.ispan.gym.service.order;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 逾時未付款訂單的自動取消排程。
 *
 * <p>訂單的庫存是在建單當下就扣掉（見 {@link OrderService#createOrderFromCart}），
 * 使用者被導去綠界後放棄付款、或本機收不到綠界的付款結果通知時，訂單會一直卡在 UNPAID，
 * 被扣的庫存也一直還不回來。這支排程定期把超過 {@code app.order.unpaid-ttl} 仍未付款的訂單
 * 比照手動取消處理（見 {@link OrderService#expireStaleUnpaidOrders}）。
 *
 * <p>寫法比照 {@link tw.com.ispan.gym.service.idempotency.IdempotencyCleanupScheduler}：
 * 直接注入 service、不注入 {@code Clock}。
 */
@Component
public class StaleOrderCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(StaleOrderCleanupScheduler.class);

    private final OrderService orderService;
    private final Duration unpaidTtl;

    public StaleOrderCleanupScheduler(OrderService orderService,
            @Value("${app.order.unpaid-ttl:PT30M}") Duration unpaidTtl) {
        this.orderService = orderService;
        this.unpaidTtl = unpaidTtl;
    }

    @Scheduled(fixedDelayString = "${app.order.cleanup-interval:PT5M}", initialDelayString = "PT1M")
    public void cancelStaleUnpaidOrders() {
        try {
            int cancelled = orderService.expireStaleUnpaidOrders(unpaidTtl);
            if (cancelled > 0) {
                log.info("已自動取消 {} 筆逾時未付款訂單（TTL {}）", cancelled, unpaidTtl);
            }
        } catch (Exception ex) {
            // 單次失敗不影響下一輪；記錄後放行
            log.error("自動取消逾時未付款訂單時發生例外", ex);
        }
    }
}
