package tw.com.ispan.gym.event.order;

import java.time.LocalDate;

/**
 * 退款完成領域事件。訂單域只負責宣告「這筆退款完成了」，
 * 誰關心（如報表域標記快照過時）自己以 @EventListener 訂閱——
 * 依賴方向維持「衍生模組依賴訂單」，訂單域不知道下游存在。
 *
 * @param paidDate      原訂單的付款日（營收歸屬日）：涵蓋這天的快照，營收欄位已失真
 * @param completedDate 退款完成日（退款彙總的歸屬日）：涵蓋這天的快照，退款筆數／金額欄位已失真。
 *                      兩個日期可能落在不同區間（8 月付款、9 月退款），下游兩邊都要看
 */
public record RefundCompletedEvent(Integer orderId, LocalDate paidDate, LocalDate completedDate) {
}
