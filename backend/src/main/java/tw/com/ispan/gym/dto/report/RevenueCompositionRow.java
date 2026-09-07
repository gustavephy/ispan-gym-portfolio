package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;

/**
 * 營收組成列：把營收拆成「商品／私人課程／會籍訂閱」三類。
 * type 為顯示用中文字串，由 service 依 order_item 的雙 FK（product／course）
 * 與訂單的 subscriptionPlan 判別，前端直接畫圓餅。
 */
public record RevenueCompositionRow(
        String type,
        BigDecimal grossAmount) {
}
