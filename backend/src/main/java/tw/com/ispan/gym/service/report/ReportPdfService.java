package tw.com.ispan.gym.service.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.common.pdf.PdfLineChart;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.report.ReportBreakdown;
import tw.com.ispan.gym.entity.report.ReportDailyTotal;
import tw.com.ispan.gym.entity.report.ReportSalesItem;
import tw.com.ispan.gym.entity.report.ReportSnapshot;
import tw.com.ispan.gym.enums.report.ReportBreakdownDimension;
import tw.com.ispan.gym.enums.report.ReportItemType;
import tw.com.ispan.gym.enums.report.ReportType;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.exception.aihealthchat.PdfGenerationException;
import tw.com.ispan.gym.repository.report.ReportSnapshotRepository;

/**
 * 營運報表快照 → PDF。字型與繪製方式沿用 aihealthchat.PdfReportService 的定案：
 * 內嵌 GenYoMin TrueType（CID-keyed CFF 有 OpenPDF 子集化 bug，不可用 Noto OTF），
 * BaseFont.createFont 有靜態快取，與課表服務重複載入同一字型檔不會付兩次解析成本。
 *
 * <p>只轉「已生成的快照」——即時統計（/stats）要匯出前先走 POST 生成快照，
 * 讓 PDF 上的數字永遠有一份可回查的資料庫紀錄對應，不產生「印出來卻查不到」的孤兒報表。
 * staleAt 非 null（生成後涵蓋期間內發生過退款）時在 PDF 開頭以醒目警示標註，
 * 紙本流通後讀者仍能得知數據可能過時。
 */
@Service
public class ReportPdfService {
    private static final Logger log = LoggerFactory.getLogger(ReportPdfService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    /** 金額千分位、小數至多兩位（整數金額不補 .00） */
    private static final String MONEY_PATTERN = "#,##0.##";

    private final ReportSnapshotRepository snapshots;
    private final BaseFont regularFont;
    private final BaseFont boldFont;

    public ReportPdfService(ReportSnapshotRepository snapshots) {
        this.snapshots = snapshots;
        this.regularFont = loadEmbeddedFont("fonts/GenYoMin-R.ttc,0");
        this.boldFont = loadEmbeddedFont("fonts/GenYoMin-B.ttc,0");
    }

    /** 字型檔缺失屬部署錯誤，啟動時直接失敗比第一次下載報表才 500 好 */
    private static BaseFont loadEmbeddedFont(String classpath) {
        try {
            return BaseFont.createFont(classpath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        } catch (Exception ex) {
            throw new IllegalStateException("載入內嵌中文字型失敗：" + classpath, ex);
        }
    }

    /** 需在交易內展開 LAZY 的排行明細與產生者 */
    @Transactional(readOnly = true)
    public byte[] generateReportPdf(Integer reportId) {
        ReportSnapshot snapshot = snapshots.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到報表資料"));
        try {
            return render(snapshot);
        } catch (Exception ex) {
            log.error("營運報表 PDF 產生失敗，reportId={}", reportId, ex);
            throw new PdfGenerationException("報表 PDF 產生失敗，請稍後再試");
        }
    }

    private byte[] render(ReportSnapshot snapshot) throws Exception {
        Font titleFont = new Font(boldFont, 18, Font.NORMAL);
        Font headerFont = new Font(boldFont, 13, Font.NORMAL);
        Font bodyFont = new Font(regularFont, 10, Font.NORMAL);
        Font mutedFont = new Font(regularFont, 9, Font.ITALIC, Color.GRAY);
        Font staleFont = new Font(boldFont, 10, Font.NORMAL, new Color(0xC0, 0x39, 0x2B));
        Font tableHeaderFont = new Font(boldFont, 10, Font.NORMAL, Color.WHITE);

        Document document = new Document(PageSize.A4, 40, 40, 50, 50);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(document, out);
        document.open();

        document.add(new Paragraph("威力健身房 營運報表", titleFont));
        document.add(Chunk.NEWLINE);
        document.add(new Paragraph("報表類型：" + describeType(snapshot.getReportType())
                + "（編號 #" + snapshot.getId() + "）", bodyFont));
        document.add(new Paragraph("統計期間：" + snapshot.getPeriodStart().format(DATE_FMT)
                + " ～ " + snapshot.getPeriodEnd().format(DATE_FMT) + "（含首尾日）", bodyFont));
        String generatedAt = snapshot.getGeneratedAt() == null ? "" : snapshot.getGeneratedAt().format(DATETIME_FMT);
        document.add(new Paragraph("生成時間：" + generatedAt + describeGeneratedBy(snapshot.getGeneratedBy()),
                bodyFont));

        if (snapshot.getStaleAt() != null) {
            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("注意：本報表生成後（" + snapshot.getStaleAt().format(DATETIME_FMT)
                    + "）涵蓋期間內曾發生退款，數據可能已過時，建議重新生成後再行引用。", staleFont));
        }
        document.add(Chunk.NEWLINE);

        document.add(new Paragraph("總覽", headerFont));
        document.add(buildSummaryTable(snapshot, bodyFont, tableHeaderFont));

        addCategoryShareSection(document, snapshot, headerFont, bodyFont, mutedFont, tableHeaderFont);
        addTrendSection(document, writer, snapshot, headerFont, mutedFont);
        addBreakdownSection(document, snapshot, headerFont, mutedFont, bodyFont, tableHeaderFont);

        document.add(new Paragraph("銷售排行", headerFont));
        if (snapshot.getItems().isEmpty()) {
            document.add(new Paragraph("本期間無銷售明細", mutedFont));
        } else {
            document.add(buildRankingTable(snapshot, bodyFont, tableHeaderFont));
        }

        addRefundAndMemberSection(document, snapshot, headerFont, bodyFont, mutedFont, tableHeaderFont);

        document.add(new Paragraph("營收口徑：已付款／已出貨／已完成訂單，依付款時間歸屬；"
                + "訂單分佈依下單日統計；金額單位為新臺幣元。", mutedFont));

        document.close();
        return out.toByteArray();
    }

    /** 品類小計與占比：自排行明細分組推導，占比分母為明細銷售額合計（非訂單總額，兩者差在訂單層折扣） */
    private void addCategoryShareSection(Document document, ReportSnapshot snapshot, Font headerFont,
            Font bodyFont, Font mutedFont, Font tableHeaderFont) throws DocumentException {
        if (snapshot.getItems().isEmpty()) {
            return;
        }
        Map<ReportItemType, long[]> quantityByType = new LinkedHashMap<>();
        Map<ReportItemType, BigDecimal> amountByType = new LinkedHashMap<>();
        BigDecimal itemsTotal = BigDecimal.ZERO;
        for (ReportSalesItem item : snapshot.getItems()) {
            BigDecimal amount = nullSafe(item.getGrossAmount());
            quantityByType.computeIfAbsent(item.getProductType(), t -> new long[1])[0] += item.getQuantitySold();
            amountByType.merge(item.getProductType(), amount, BigDecimal::add);
            itemsTotal = itemsTotal.add(amount);
        }

        document.add(new Paragraph("品類小計與占比", headerFont));
        DecimalFormat money = new DecimalFormat(MONEY_PATTERN);
        PdfPTable table = new PdfPTable(new float[] { 1.6f, 1.2f, 1.6f, 1.2f });
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(12);
        addHeaderCell(table, "品類", tableHeaderFont);
        addHeaderCell(table, "銷售數量", tableHeaderFont);
        addHeaderCell(table, "銷售額", tableHeaderFont);
        addHeaderCell(table, "占比", tableHeaderFont);
        for (Map.Entry<ReportItemType, BigDecimal> entry : amountByType.entrySet()) {
            addBodyCell(table, describeItemType(entry.getKey()), bodyFont, Element.ALIGN_CENTER);
            addBodyCell(table, String.valueOf(quantityByType.get(entry.getKey())[0]), bodyFont, Element.ALIGN_RIGHT);
            addBodyCell(table, money.format(entry.getValue()), bodyFont, Element.ALIGN_RIGHT);
            addBodyCell(table, sharePercent(entry.getValue(), itemsTotal), bodyFont, Element.ALIGN_RIGHT);
        }
        document.add(table);
    }

    /** 每日營收趨勢：讀快照固化的 dailyTotals（生成當下凍結，不即時重算） */
    private void addTrendSection(Document document, PdfWriter writer, ReportSnapshot snapshot,
            Font headerFont, Font mutedFont) throws Exception {
        document.add(new Paragraph("每日營收趨勢", headerFont));
        List<ReportDailyTotal> dailyTotals = snapshot.getDailyTotals();
        if (dailyTotals.size() < 2) {
            document.add(new Paragraph("期間內有營收的天數不足（至少需 2 天才能繪製趨勢圖）", mutedFont));
            return;
        }
        List<LocalDate> dates = dailyTotals.stream().map(ReportDailyTotal::getStatDate).toList();
        List<BigDecimal> values = dailyTotals.stream().map(ReportDailyTotal::getRevenue).toList();
        document.add(PdfLineChart.buildChart(writer, regularFont, dates, values,
                "每日營收 (元)", new Color(0x2F, 0x6F, 0xED)));
    }

    /** 狀態／付款方式分佈：讀快照固化的 breakdowns；舊快照（擴充前生成）無資料則略過整節 */
    private void addBreakdownSection(Document document, ReportSnapshot snapshot, Font headerFont,
            Font mutedFont, Font bodyFont, Font tableHeaderFont) throws DocumentException {
        List<ReportBreakdown> statusRows = snapshot.getBreakdowns().stream()
                .filter(b -> b.getDimension() == ReportBreakdownDimension.STATUS).toList();
        List<ReportBreakdown> paymentRows = snapshot.getBreakdowns().stream()
                .filter(b -> b.getDimension() == ReportBreakdownDimension.PAYMENT_METHOD).toList();
        if (statusRows.isEmpty() && paymentRows.isEmpty()) {
            return;
        }
        DecimalFormat money = new DecimalFormat(MONEY_PATTERN);

        if (!statusRows.isEmpty()) {
            document.add(new Paragraph("訂單狀態分佈（依下單日）", headerFont));
            PdfPTable table = new PdfPTable(new float[] { 1.6f, 1.2f, 1.6f });
            table.setWidthPercentage(100);
            table.setSpacingBefore(4);
            table.setSpacingAfter(12);
            addHeaderCell(table, "狀態", tableHeaderFont);
            addHeaderCell(table, "訂單數", tableHeaderFont);
            addHeaderCell(table, "金額合計", tableHeaderFont);
            for (ReportBreakdown row : statusRows) {
                addBodyCell(table, describeOrderStatus(row.getItemKey()), bodyFont, Element.ALIGN_CENTER);
                addBodyCell(table, String.valueOf(row.getOrderCount()), bodyFont, Element.ALIGN_RIGHT);
                addBodyCell(table, money.format(nullSafe(row.getAmount())), bodyFont, Element.ALIGN_RIGHT);
            }
            document.add(table);
        }

        if (!paymentRows.isEmpty()) {
            document.add(new Paragraph("付款方式分佈", headerFont));
            PdfPTable table = new PdfPTable(new float[] { 2.4f, 1.2f });
            table.setWidthPercentage(100);
            table.setSpacingBefore(4);
            table.setSpacingAfter(12);
            addHeaderCell(table, "付款方式", tableHeaderFont);
            addHeaderCell(table, "訂單數", tableHeaderFont);
            for (ReportBreakdown row : paymentRows) {
                addBodyCell(table, row.getItemKey(), bodyFont, Element.ALIGN_CENTER);
                addBodyCell(table, String.valueOf(row.getOrderCount()), bodyFont, Element.ALIGN_RIGHT);
            }
            document.add(table);
        }
    }

    /** 退款與會員動態：延伸統計為 2026-08-17 擴充，舊快照無資料時略過 */
    private void addRefundAndMemberSection(Document document, ReportSnapshot snapshot, Font headerFont,
            Font bodyFont, Font mutedFont, Font tableHeaderFont) throws DocumentException {
        if (snapshot.getRefundCount() == null) {
            return;
        }
        document.add(new Paragraph("退款與會員動態", headerFont));
        DecimalFormat money = new DecimalFormat(MONEY_PATTERN);
        PdfPTable table = new PdfPTable(new float[] { 1f, 1f, 1f, 1f });
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(12);
        addHeaderCell(table, "完成退款筆數", tableHeaderFont);
        addHeaderCell(table, "退款金額", tableHeaderFont);
        addHeaderCell(table, "新註冊會員", tableHeaderFont);
        addHeaderCell(table, "新生效訂閱", tableHeaderFont);
        addBodyCell(table, String.valueOf(snapshot.getRefundCount()), bodyFont, Element.ALIGN_CENTER);
        addBodyCell(table, money.format(nullSafe(snapshot.getRefundAmount())), bodyFont, Element.ALIGN_CENTER);
        addBodyCell(table, String.valueOf(snapshot.getNewMemberCount()), bodyFont, Element.ALIGN_CENTER);
        addBodyCell(table, String.valueOf(snapshot.getNewSubscriptionCount()), bodyFont, Element.ALIGN_CENTER);
        document.add(table);
    }

    private PdfPTable buildSummaryTable(ReportSnapshot snapshot, Font bodyFont, Font tableHeaderFont) {
        DecimalFormat money = new DecimalFormat(MONEY_PATTERN);
        PdfPTable table = new PdfPTable(new float[] { 1f, 1f, 1f, 1f });
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(12);
        addHeaderCell(table, "訂單數", tableHeaderFont);
        addHeaderCell(table, "營收總額", tableHeaderFont);
        addHeaderCell(table, "折扣總額", tableHeaderFont);
        addHeaderCell(table, "平均客單價", tableHeaderFont);
        addBodyCell(table, String.valueOf(snapshot.getOrderCount()), bodyFont, Element.ALIGN_CENTER);
        addBodyCell(table, money.format(snapshot.getTotalAmount()), bodyFont, Element.ALIGN_CENTER);
        addBodyCell(table, money.format(snapshot.getDiscountAmount()), bodyFont, Element.ALIGN_CENTER);
        addBodyCell(table, averageOrderValue(snapshot, money), bodyFont, Element.ALIGN_CENTER);
        return table;
    }

    /** 平均客單價＝營收總額 ÷ 訂單數（元，取整）；無訂單顯示「—」 */
    private String averageOrderValue(ReportSnapshot snapshot, DecimalFormat money) {
        if (snapshot.getOrderCount() == null || snapshot.getOrderCount() == 0) {
            return "—";
        }
        return money.format(snapshot.getTotalAmount()
                .divide(BigDecimal.valueOf(snapshot.getOrderCount()), 0, RoundingMode.HALF_UP));
    }

    private PdfPTable buildRankingTable(ReportSnapshot snapshot, Font bodyFont, Font tableHeaderFont) {
        DecimalFormat money = new DecimalFormat(MONEY_PATTERN);
        BigDecimal itemsTotal = snapshot.getItems().stream()
                .map(i -> nullSafe(i.getGrossAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        PdfPTable table = new PdfPTable(new float[] { 0.8f, 1.2f, 3.2f, 1.2f, 1.6f, 1f });
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(12);
        addHeaderCell(table, "排名", tableHeaderFont);
        addHeaderCell(table, "品項類型", tableHeaderFont);
        addHeaderCell(table, "品項名稱", tableHeaderFont);
        addHeaderCell(table, "銷售數量", tableHeaderFont);
        addHeaderCell(table, "銷售額", tableHeaderFont);
        addHeaderCell(table, "占比", tableHeaderFont);

        // items 由 entity @OrderBy("rankNo ASC") 保證排序
        for (ReportSalesItem item : snapshot.getItems()) {
            addBodyCell(table, String.valueOf(item.getRankNo()), bodyFont, Element.ALIGN_CENTER);
            addBodyCell(table, describeItemType(item.getProductType()), bodyFont, Element.ALIGN_CENTER);
            addBodyCell(table, item.getProductName(), bodyFont, Element.ALIGN_LEFT);
            addBodyCell(table, String.valueOf(item.getQuantitySold()), bodyFont, Element.ALIGN_RIGHT);
            addBodyCell(table, money.format(nullSafe(item.getGrossAmount())), bodyFont, Element.ALIGN_RIGHT);
            addBodyCell(table, sharePercent(nullSafe(item.getGrossAmount()), itemsTotal), bodyFont,
                    Element.ALIGN_RIGHT);
        }
        return table;
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String describeGeneratedBy(Employee generatedBy) {
        if (generatedBy == null) {
            return "";
        }
        return "　產生者：" + generatedBy.getLastName() + generatedBy.getFirstName();
    }

    private String describeType(ReportType type) {
        return switch (type) {
            case DAILY -> "日報";
            case MONTHLY -> "月報";
            case CUSTOM -> "自訂區間";
        };
    }

    private String describeItemType(ReportItemType type) {
        return switch (type) {
            case PRODUCT -> "商品";
            case COURSE -> "私教課程";
            case SUBSCRIPTION -> "訂閱方案";
        };
    }

    /** itemKey 為 OrderStatus 名稱字串（快照固化值）；未知值原樣顯示，容忍未來新增狀態 */
    private String describeOrderStatus(String status) {
        return switch (status) {
            case "UNPAID" -> "未付款";
            case "PAID" -> "已付款";
            case "SHIPPED" -> "已出貨";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            case "FAILED" -> "付款失敗";
            case "REFUNDED" -> "已退款";
            default -> status;
        };
    }

    /** 占比字串（一位小數）；分母為零時顯示「—」 */
    private String sharePercent(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return "—";
        }
        return part.multiply(BigDecimal.valueOf(100))
                .divide(total, 1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setBackgroundColor(new Color(0x40, 0x40, 0x40));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(4);
        table.addCell(cell);
    }

    private void addBodyCell(PdfPTable table, String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(4);
        table.addCell(cell);
    }
}
