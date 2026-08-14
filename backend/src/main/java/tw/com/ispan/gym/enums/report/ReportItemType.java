package tw.com.ispan.gym.enums.report;

/**
 * 報表品項型別（report_sales_item.product_type）。
 * 取代舊 SKU 制的 ProductType：現制的品項來源是 order_item 雙 FK
 *（PRODUCT＝product_id、COURSE＝private_course_id）與訂閱單
 *（SUBSCRIPTION＝orders.subscription_plan_id，無明細列）。
 */
public enum ReportItemType { PRODUCT, COURSE, SUBSCRIPTION }
