package tw.com.ispan.gym.service.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;

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

import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.report.ReportSalesItem;
import tw.com.ispan.gym.entity.report.ReportSnapshot;
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

    private byte[] render(ReportSnapshot snapshot) throws DocumentException {
        Font titleFont = new Font(boldFont, 18, Font.NORMAL);
        Font headerFont = new Font(boldFont, 13, Font.NORMAL);
        Font bodyFont = new Font(regularFont, 10, Font.NORMAL);
        Font mutedFont = new Font(regularFont, 9, Font.ITALIC, Color.GRAY);
        Font staleFont = new Font(boldFont, 10, Font.NORMAL, new Color(0xC0, 0x39, 0x2B));
        Font tableHeaderFont = new Font(boldFont, 10, Font.NORMAL, Color.WHITE);

        Document document = new Document(PageSize.A4, 40, 40, 50, 50);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, out);
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

        document.add(new Paragraph("銷售排行", headerFont));
        if (snapshot.getItems().isEmpty()) {
            document.add(new Paragraph("本期間無銷售明細", mutedFont));
        } else {
            document.add(buildRankingTable(snapshot, bodyFont, tableHeaderFont));
        }

        document.add(new Paragraph("營收口徑：已付款／已出貨／已完成訂單，依付款時間歸屬；金額單位為新臺幣元。",
                mutedFont));

        document.close();
        return out.toByteArray();
    }

    private PdfPTable buildSummaryTable(ReportSnapshot snapshot, Font bodyFont, Font tableHeaderFont) {
        DecimalFormat money = new DecimalFormat(MONEY_PATTERN);
        PdfPTable table = new PdfPTable(new float[] { 1f, 1f, 1f });
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(12);
        addHeaderCell(table, "訂單數", tableHeaderFont);
        addHeaderCell(table, "營收總額", tableHeaderFont);
        addHeaderCell(table, "折扣總額", tableHeaderFont);
        addBodyCell(table, String.valueOf(snapshot.getOrderCount()), bodyFont, Element.ALIGN_CENTER);
        addBodyCell(table, money.format(snapshot.getTotalAmount()), bodyFont, Element.ALIGN_CENTER);
        addBodyCell(table, money.format(snapshot.getDiscountAmount()), bodyFont, Element.ALIGN_CENTER);
        return table;
    }

    private PdfPTable buildRankingTable(ReportSnapshot snapshot, Font bodyFont, Font tableHeaderFont) {
        DecimalFormat money = new DecimalFormat(MONEY_PATTERN);
        PdfPTable table = new PdfPTable(new float[] { 0.8f, 1.2f, 3.6f, 1.2f, 1.6f });
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(12);
        addHeaderCell(table, "排名", tableHeaderFont);
        addHeaderCell(table, "品項類型", tableHeaderFont);
        addHeaderCell(table, "品項名稱", tableHeaderFont);
        addHeaderCell(table, "銷售數量", tableHeaderFont);
        addHeaderCell(table, "銷售額", tableHeaderFont);

        // items 由 entity @OrderBy("rankNo ASC") 保證排序
        for (ReportSalesItem item : snapshot.getItems()) {
            addBodyCell(table, String.valueOf(item.getRankNo()), bodyFont, Element.ALIGN_CENTER);
            addBodyCell(table, describeItemType(item.getProductType()), bodyFont, Element.ALIGN_CENTER);
            addBodyCell(table, item.getProductName(), bodyFont, Element.ALIGN_LEFT);
            addBodyCell(table, String.valueOf(item.getQuantitySold()), bodyFont, Element.ALIGN_RIGHT);
            addBodyCell(table, money.format(nullSafe(item.getGrossAmount())), bodyFont, Element.ALIGN_RIGHT);
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
