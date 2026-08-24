package tw.com.ispan.gym.enums.report;

/** 報表分佈明細的分組維度（report_breakdown.dimension） */
public enum ReportBreakdownDimension {
    /** 依訂單狀態；item_key 為 OrderStatus 名稱，amount 為該狀態金額合計 */
    STATUS,
    /** 依付款方式；item_key 為 orders.payment_method 原值（未付款為 UNSET），amount 為 null */
    PAYMENT_METHOD
}
