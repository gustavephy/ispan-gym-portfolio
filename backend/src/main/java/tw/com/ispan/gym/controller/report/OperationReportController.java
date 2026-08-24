package tw.com.ispan.gym.controller.report;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tw.com.ispan.gym.dto.report.GenerateReportRequest;
import tw.com.ispan.gym.dto.report.ReportDetailResponse;
import tw.com.ispan.gym.dto.report.ReportSummaryResponse;
import tw.com.ispan.gym.dto.report.SalesStatsResponse;
import tw.com.ispan.gym.enums.report.ReportItemType;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.report.OperationReportService;
import tw.com.ispan.gym.service.report.ReportPdfService;

/** 營運報表。掛在 /api/admin 下，由 SecurityConfig 的 hasRole("ADMIN") 統一把關。 */
@RestController
@RequestMapping("/api/admin/reports")
public class OperationReportController {

    private final OperationReportService reportService;
    private final ReportPdfService reportPdfService;
    private final CurrentUserProvider currentUser;

    public OperationReportController(OperationReportService reportService,
                                     ReportPdfService reportPdfService,
                                     CurrentUserProvider currentUser) {
        this.reportService = reportService;
        this.reportPdfService = reportPdfService;
        this.currentUser = currentUser;
    }

    /** 歷史報表列表 */
    @GetMapping
    public List<ReportSummaryResponse> listReports() {
        return reportService.listReports();
    }

    /**
     * 即時統計：依品項型別（PRODUCT/COURSE/SUBSCRIPTION，可省略＝全部）篩選時間區間，
     * 回聚合值與排行（不落快照）。
     * 註：路徑須排在 /{reportId} 之前的字面路徑，避免 "stats" 被當成 id。
     */
    @GetMapping("/stats")
    public SalesStatsResponse liveStats(
            @RequestParam(required = false) ReportItemType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int top) {
        return reportService.liveStats(type, from, to, top);
    }

    /** 單份報表含排行明細 */
    @GetMapping("/{reportId}")
    public ReportDetailResponse getReport(@PathVariable Integer reportId) {
        return reportService.getReport(reportId);
    }

    /** 單份報表匯出 PDF（僅限已生成的快照；即時統計請先生成快照再匯出） */
    @GetMapping("/{reportId}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Integer reportId) {
        byte[] pdf = reportPdfService.generateReportPdf(reportId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"operation-report-" + reportId + ".pdf\"")
                .body(pdf);
    }

    /** 手動生成（或重算）快照；操作者以帳號 id 記錄（service 反查 Employee） */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReportDetailResponse generate(@Valid @RequestBody GenerateReportRequest request) {
        return reportService.generate(request, currentUser.requireCurrentUserId());
    }

    /** 刪除單份歷史報表 */
    @DeleteMapping("/{reportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Integer reportId) {
        reportService.delete(reportId);
    }
}
