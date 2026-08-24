package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;

/**
 * 分佈明細列。itemKey 依所屬清單解讀：狀態分佈＝OrderStatus 名稱、
 * 付款方式分佈＝orders.payment_method 原值；amount 僅狀態分佈有值。
 */
public record ReportBreakdownResponse(
        String itemKey,
        Long orderCount,
        BigDecimal amount) {
}
