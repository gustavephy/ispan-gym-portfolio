package tw.com.ispan.gym.service.report;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Service;

import tw.com.ispan.gym.dto.report.ReportDetailResponse;
import tw.com.ispan.gym.dto.report.ReportSalesItemResponse;

/**
 * 把已生成的報表快照的「品項排行」輸出成 CSV，與 {@link ReportPdfService} 的 PDF 匯出並列，
 * 方便丟進 Excel／試算表做進一步分析。內容口徑完全依快照，不重算。
 */
@Service
public class ReportCsvService {
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private final OperationReportService reportService;

    public ReportCsvService(OperationReportService reportService) {
        this.reportService = reportService;
    }

    /** 產出品項排行 CSV（UTF-8 with BOM，Excel 開中文才不亂碼） */
    public byte[] generateRankingCsv(Integer reportId) {
        ReportDetailResponse report = reportService.getReport(reportId);

        StringBuilder sb = new StringBuilder();
        sb.append("排名,型別,品項名稱,售出數量,銷售金額\n");
        for (ReportSalesItemResponse item : report.items()) {
            sb.append(item.rankNo()).append(',')
                    .append(item.productType()).append(',')
                    .append(csv(item.productName())).append(',')
                    .append(item.quantitySold()).append(',')
                    .append(item.grossAmount().toPlainString()).append('\n');
        }

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    /** RFC 4180：含逗號／引號／換行的欄位用雙引號包起來，內部的雙引號成對跳脫 */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
