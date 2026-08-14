package tw.com.ispan.gym.event.order;

import java.time.LocalDate;

/**
 * 退款完成領域事件。訂單域只負責宣告「這筆退款完成了」，
 * 誰關心（如報表域標記快照過時）自己以 @EventListener 訂閱——
 * 依賴方向維持「衍生模組依賴訂單」，訂單域不知道下游存在。
 *
 * @param paidDate 原訂單的付款日（營收歸屬日），下游據此找出受影響的統計區間
 */
public record RefundCompletedEvent(Long orderId, LocalDate paidDate) {
}
