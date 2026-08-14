package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import tw.com.ispan.gym.enums.report.ReportItemType;

/** ?瑕???桀?嚗翰?扳?蝝啗??單????梁嚗?*/
public record ReportSalesItemResponse(
        Integer rankNo,
        Integer productId,
        ReportItemType productType,
        String productName,
        Long quantitySold,
        BigDecimal grossAmount) {
}

