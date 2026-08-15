package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import tw.com.ispan.gym.enums.report.ReportItemType;

/** 排行明細列（快照與即時統計共用格式） */
public record ReportSalesItemResponse(
        Integer rankNo,
        Integer productId,
        ReportItemType productType,
        String productName,
        Long quantitySold,
        BigDecimal grossAmount) {
}
