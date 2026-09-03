package tw.com.ispan.gym.controller.report;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tw.com.ispan.gym.dto.report.CouponEffectRow;
import tw.com.ispan.gym.dto.report.GenerateReportRequest;
import tw.com.ispan.gym.dto.report.GroupClassFillRow;
import tw.com.ispan.gym.dto.report.MemberDailyRow;
import tw.com.ispan.gym.dto.report.TrainerSlotRow;
import tw.com.ispan.gym.dto.report.ReportDetailResponse;
import tw.com.ispan.gym.dto.report.ReportSummaryResponse;
import tw.com.ispan.gym.dto.report.SalesStatsResponse;
import tw.com.ispan.gym.dto.report.VoidReportRequest;
import tw.com.ispan.gym.enums.report.ReportItemType;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.report.OperationReportService;
import tw.com.ispan.gym.service.report.ReportCsvService;
import tw.com.ispan.gym.service.report.ReportPdfService;

/** 營運報表。掛在 /api/admin 下，由 SecurityConfig 的 hasRole("ADMIN") 統一把關。 */
@RestController
@RequestMapping("/api/admin/reports")
public class OperationReportController {

    private final OperationReportService reportService;
    private final ReportPdfService reportPdfService;
    private final ReportCsvService reportCsvService;
    private final CurrentUserProvider currentUser;

    public OperationReportController(OperationReportService reportService,
                                     ReportPdfService reportPdfService,
                                     ReportCsvService reportCsvService,
                                     CurrentUserProvider currentUser) {
        this.reportService = reportService;
        this.reportPdfService = reportPdfService;
        this.reportCsvService = reportCsvService;
        this.currentUser = currentUser;
    }

    /** 歷史報表列表（現行版）；預設不含已作廢，includeVoided=true 連作廢的一起列 */
    @GetMapping
    public List<ReportSummaryResponse> listReports(
            @RequestParam(defaultValue = "false") boolean includeVoided) {
        return reportService.listReports(includeVoided);
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

    /** 團課滿座率（即時統計，不落快照）；字面路徑同樣須排在 /{reportId} 之前 */
    @GetMapping("/group-class-fill")
    public List<GroupClassFillRow> groupClassFill(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.groupClassFill(from, to);
    }

    /** 每日新會員趨勢（即時統計，不落快照） */
    @GetMapping("/member-growth")
    public List<MemberDailyRow> memberGrowth(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.memberGrowth(from, to);
    }

    /** 教練時段使用率（即時統計，不落快照） */
    @GetMapping("/trainer-utilization")
    public List<TrainerSlotRow> trainerUtilization(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.trainerUtilization(from, to);
    }

    /** 優惠券成效（即時統計，看券的整個生命週期，不隨期間篩選） */
    @GetMapping("/coupon-effect")
    public List<CouponEffectRow> couponEffect() {
        return reportService.couponEffect();
    }

    /** 單份報表含排行明細 */
    @GetMapping("/{reportId}")
    public ReportDetailResponse getReport(@PathVariable Integer reportId) {
        return reportService.getReport(reportId);
    }

    /** 同型別同區間的全部版本（現行版第一筆）；append-only 版本化的歷史入口 */
    @GetMapping("/{reportId}/versions")
    public List<ReportSummaryResponse> versions(@PathVariable Integer reportId) {
        return reportService.versions(reportId);
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

    /** 單份報表的品項排行匯出 CSV（UTF-8 with BOM，可直接用 Excel 開） */
    @GetMapping("/{reportId}/csv")
    public ResponseEntity<byte[]> downloadCsv(@PathVariable Integer reportId) {
        byte[] csv = reportCsvService.generateRankingCsv(reportId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"operation-report-" + reportId + "-ranking.csv\"")
                .body(csv);
    }

    /** 手動生成（或重算）快照；操作者以帳號 id 記錄（service 反查 Employee） */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReportDetailResponse generate(@Valid @RequestBody GenerateReportRequest request) {
        return reportService.generate(request, currentUser.requireCurrentUserId());
    }

    /**
     * 作廢單份快照（現行版或歷史版皆可）。快照不提供刪除：報表是稽核紀錄，
     * 誤生成只能作廢——列表預設隱藏、資料保留，記錄作廢人與原因。body 可省略。
     */
    @PostMapping("/{reportId}/void")
    public ReportSummaryResponse voidReport(@PathVariable Integer reportId,
                                            @Valid @RequestBody(required = false) VoidReportRequest request) {
        String reason = request == null ? null : request.reason();
        return reportService.voidSnapshot(reportId, currentUser.requireCurrentUserId(), reason);
    }
}
