package tw.com.ispan.gym.service.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.report.ReportDetailResponse;
import tw.com.ispan.gym.dto.report.ReportSalesItemResponse;
import tw.com.ispan.gym.enums.report.ReportItemType;
import tw.com.ispan.gym.enums.report.ReportType;

/** 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。 */
class ReportCsvServiceTest {

    private final OperationReportService reportService = mock(OperationReportService.class);
    private final ReportCsvService service = new ReportCsvService(reportService);

    private ReportDetailResponse reportWith(List<ReportSalesItemResponse> items) {
        return new ReportDetailResponse(1, ReportType.MONTHLY, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                10L, new BigDecimal("1000"), BigDecimal.ZERO, null, null, items,
                List.of(), List.of(), List.of(), 0L, BigDecimal.ZERO, 0L, 0L);
    }

    @Test
    void 匯出品項排行為含表頭與BOM的CSV() {
        when(reportService.getReport(1)).thenReturn(reportWith(List.of(
                new ReportSalesItemResponse(1, 100, ReportItemType.PRODUCT, "乳清蛋白", 12L, new BigDecimal("12000")),
                new ReportSalesItemResponse(2, 7, ReportItemType.COURSE, "一對一重訓", 3L, new BigDecimal("19500")))));

        byte[] csv = service.generateRankingCsv(1);
        String text = new String(csv, StandardCharsets.UTF_8);

        assertThat(csv).startsWith(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
        assertThat(text).contains("排名,型別,品項名稱,售出數量,銷售金額");
        assertThat(text).contains("1,PRODUCT,乳清蛋白,12,12000");
        assertThat(text).contains("2,COURSE,一對一重訓,3,19500");
    }

    @Test
    void 品項名稱含逗號時以雙引號包起來() {
        when(reportService.getReport(1)).thenReturn(reportWith(List.of(
                new ReportSalesItemResponse(1, 100, ReportItemType.PRODUCT, "乳清,巧克力", 1L, new BigDecimal("100")))));

        String text = new String(service.generateRankingCsv(1), StandardCharsets.UTF_8);

        assertThat(text).contains("\"乳清,巧克力\"");
    }
}
