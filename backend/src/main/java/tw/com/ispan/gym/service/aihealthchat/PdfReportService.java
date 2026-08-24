package tw.com.ispan.gym.service.aihealthchat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;

import tw.com.ispan.gym.common.pdf.PdfLineChart;
import tw.com.ispan.gym.entity.aihealthchat.AiHealthPlan;
import tw.com.ispan.gym.entity.aihealthchat.InbodyRecord;
import tw.com.ispan.gym.exception.aihealthchat.AiHealthPlanNotFoundException;
import tw.com.ispan.gym.exception.aihealthchat.ForbiddenAiHealthPlanAccessException;
import tw.com.ispan.gym.exception.aihealthchat.PdfGenerationException;
import tw.com.ispan.gym.repository.aihealthchat.AiHealthPlanRepository;
import tw.com.ispan.gym.repository.aihealthchat.InbodyRecordRepository;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用 OpenPDF 把 AiHealthPlan.planContent（JSON）＋該會員的 InBody 歷史趨勢
 * 繪製成 PDF，以 byte[] 回傳，不落地存檔（pdf_url 欄位保留給未來如需雲端
 * 儲存時使用，目前一律串流回傳）。
 *
 * ★ 中文顯示：內嵌 resources/fonts/ 下的源樣明體 GenYoMin（思源宋體/Noto Serif
 *   的 TrueType 衍生版，OFL 授權可自由內嵌散布），OpenPDF 只子集化實際用到的
 *   字，每份 PDF 約增加數百 KB，換取任何裝置顯示一致。兩個先前的做法都不可行：
 *   （1）內建標準字型 MSung-Light＋NOT_EMBEDDED——PDF 只帶字型名稱不帶字形，
 *       瀏覽器內建檢視器等缺少可代換字型的環境中文會整段無法顯示；
 *   （2）內嵌 Noto Serif TC 官方 OTF——Noto CJK 全系列是 CID-keyed CFF 格式，
 *       OpenPDF 的 CFF 子集化有已知 bug（LibrePDF/OpenPDF#71），會產生字形
 *       對應錯亂的 PDF，所以必須用 TrueType（glyf）格式的字型。
 *   .ttc 路徑後的「,0」是 collection 索引：0=GenYoMinTW（台灣字形）、1=JP。
 *   字型檔於建構子載入一次（解析 13MB 檔案有成本），BaseFont 本身無狀態，
 *   跨執行緒共用安全（子集使用字的追蹤在各 PdfWriter 上）。
 */
@Service
public class PdfReportService {
    private static final Logger log = LoggerFactory.getLogger(PdfReportService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private record Series(String label, Color color, Function<InbodyRecord, BigDecimal> extractor) {
    }

    private static final List<Series> TREND_SERIES = List.of(
            new Series("體重 (kg)", new Color(0x2F, 0x6F, 0xED), InbodyRecord::getWeight),
            new Series("體脂率 (%)", new Color(0xE0, 0x6C, 0x2E), InbodyRecord::getBodyFat),
            new Series("骨骼肌重 (kg)", new Color(0x2E, 0xA0, 0x5A), InbodyRecord::getMuscleMass));

    private final AiHealthPlanRepository plans;
    private final InbodyRecordRepository inbodyRecords;
    private final ObjectMapper objectMapper;
    private final BaseFont regularFont;
    private final BaseFont boldFont;

    public PdfReportService(AiHealthPlanRepository plans, InbodyRecordRepository inbodyRecords,
            ObjectMapper objectMapper) {
        this.plans = plans;
        this.inbodyRecords = inbodyRecords;
        this.objectMapper = objectMapper;
        this.regularFont = loadEmbeddedFont("fonts/GenYoMin-R.ttc,0");
        this.boldFont = loadEmbeddedFont("fonts/GenYoMin-B.ttc,0");
    }

    /** 字型檔缺失屬部署錯誤，啟動時直接失敗比第一個會員下載 PDF 才 500 好。 */
    private static BaseFont loadEmbeddedFont(String classpath) {
        try {
            return BaseFont.createFont(classpath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        } catch (Exception ex) {
            throw new IllegalStateException("載入內嵌中文字型失敗：" + classpath, ex);
        }
    }

    public byte[] generatePlanPdf(Integer memberId, Integer planId) {
        AiHealthPlan plan = ownedPlan(memberId, planId);
        JsonNode content = parsePlanContent(plan.getPlanContent());

        // repository 回傳新到舊，趨勢圖要照時間先後畫；reversed() 回傳視圖不改動原 list
        List<InbodyRecord> history = inbodyRecords.findByMemberIdAndDeletedAtIsNullOrderByUpdateDateDesc(memberId)
                .reversed();

        try {
            return render(plan, content, history);
        } catch (PdfGenerationException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("課表 PDF 產生失敗，planId={}", planId, ex);
            throw new PdfGenerationException();
        }
    }

    /** IDOR 防護：與 AiAdvisorService.ownedInbodyRecord() 同樣的道理，PDF 也不可下載別人的課表。 */
    private AiHealthPlan ownedPlan(Integer memberId, Integer planId) {
        AiHealthPlan plan = plans.findById(planId)
                .orElseThrow(() -> new AiHealthPlanNotFoundException(planId));
        if (!plan.getMemberId().equals(memberId)) {
            throw new ForbiddenAiHealthPlanAccessException(planId);
        }
        return plan;
    }

    private JsonNode parsePlanContent(String planContent) {
        try {
            return objectMapper.readTree(planContent);
        } catch (Exception ex) {
            log.error("課表內容不是合法 JSON，planContent={}", planContent, ex);
            throw new PdfGenerationException();
        }
    }

    private byte[] render(AiHealthPlan plan, JsonNode content, List<InbodyRecord> history) throws Exception {
        // 粗體處用 Bold weight 的 BaseFont（style 給 NORMAL），不用 Font.BOLD——
        // 後者對內嵌字型是描邊模擬粗體，筆畫會糊掉
        Font titleFont = new Font(boldFont, 18, Font.NORMAL);
        Font headerFont = new Font(boldFont, 13, Font.NORMAL);
        Font bodyFont = new Font(regularFont, 10, Font.NORMAL);
        Font mutedFont = new Font(regularFont, 9, Font.ITALIC, Color.GRAY);
        Font tableHeaderFont = new Font(boldFont, 10, Font.NORMAL, Color.WHITE);

        Document document = new Document(PageSize.A4, 40, 40, 50, 50);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(document, out);
        document.open();

        document.add(new Paragraph("AI 個人化健身課表", titleFont));
        document.add(Chunk.NEWLINE);
        document.add(new Paragraph("訓練目標：" + describeGoal(plan.getGoal()), bodyFont));
        String createdAtText = plan.getCreatedAt() == null ? "" : plan.getCreatedAt().format(DATETIME_FMT);
        document.add(new Paragraph("產生時間：" + createdAtText, bodyFont));
        document.add(new Paragraph("AI 模型：" + plan.getModelName(), mutedFont));
        document.add(Chunk.NEWLINE);

        String summary = content.at("/summary").asText("");
        if (!summary.isBlank()) {
            document.add(new Paragraph("總結", headerFont));
            document.add(new Paragraph(summary, bodyFont));
            document.add(Chunk.NEWLINE);
        }

        addDaysSection(document, content.at("/days"), headerFont, bodyFont, tableHeaderFont);
        addDietTipsSection(document, content.at("/dietTips"), headerFont, bodyFont);

        List<Image> charts = buildTrendCharts(writer, regularFont, history);
        if (!charts.isEmpty()) {
            // 剩餘空間放不下「標題＋第一張圖」就先換頁，避免標題孤懸頁尾、
            // 圖整張被推到下一頁而中間出現一大段空白
            float needed = 20f + CHART_ROW_HEIGHT + 16f;
            if (writer.getVerticalPosition(true) - document.bottom() < needed) {
                document.newPage();
            }
        }
        document.add(new Paragraph("InBody 歷史趨勢", headerFont));
        if (charts.isEmpty()) {
            document.add(new Paragraph("量測紀錄不足（至少需 2 筆才能繪製趨勢圖）", mutedFont));
        } else {
            for (Image chart : charts) {
                document.add(chart);
            }
        }

        document.close();
        return out.toByteArray();
    }

    private void addDaysSection(Document document, JsonNode days, Font headerFont, Font bodyFont,
            Font tableHeaderFont) throws DocumentException {
        if (!days.isArray()) {
            return;
        }
        for (JsonNode day : days) {
            int dayNum = day.at("/day").asInt(0);
            String focus = day.at("/focus").asText("");
            String title = "Day " + dayNum + (focus.isBlank() ? "" : "：" + focus);
            document.add(new Paragraph(title, headerFont));

            PdfPTable table = new PdfPTable(new float[] { 3f, 1f, 1.5f, 3.5f });
            table.setWidthPercentage(100);
            table.setSpacingBefore(4);
            table.setSpacingAfter(10);
            addHeaderCell(table, "動作", tableHeaderFont);
            addHeaderCell(table, "組數", tableHeaderFont);
            addHeaderCell(table, "次數", tableHeaderFont);
            addHeaderCell(table, "備註", tableHeaderFont);

            JsonNode exercises = day.at("/exercises");
            if (exercises.isArray() && !exercises.isEmpty()) {
                for (JsonNode ex : exercises) {
                    addBodyCell(table, ex.at("/name").asText(""), bodyFont);
                    addBodyCell(table, ex.at("/sets").asText(""), bodyFont);
                    addBodyCell(table, ex.at("/reps").asText(""), bodyFont);
                    addBodyCell(table, ex.at("/notes").asText(""), bodyFont);
                }
            } else {
                PdfPCell empty = new PdfPCell(new Paragraph("休息日", bodyFont));
                empty.setColspan(4);
                empty.setPadding(4);
                table.addCell(empty);
            }
            document.add(table);
        }
    }

    private void addDietTipsSection(Document document, JsonNode dietTips, Font headerFont, Font bodyFont)
            throws DocumentException {
        if (!dietTips.isArray() || dietTips.isEmpty()) {
            return;
        }
        document.add(new Paragraph("飲食建議", headerFont));
        org.openpdf.text.List list = new org.openpdf.text.List(false, 10);
        for (JsonNode tip : dietTips) {
            list.add(new org.openpdf.text.ListItem(tip.asText(""), bodyFont));
        }
        document.add(list);
        document.add(Chunk.NEWLINE);
    }

    private void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setBackgroundColor(new Color(0x40, 0x40, 0x40));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(4);
        table.addCell(cell);
    }

    private void addBodyCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setPadding(4);
        table.addCell(cell);
    }

    private String describeGoal(String goal) {
        return switch (goal) {
            case "FAT_LOSS" -> "FAT_LOSS（減脂）";
            case "MUSCLE_GAIN" -> "MUSCLE_GAIN（增肌）";
            case "MAINTAIN" -> "MAINTAIN（維持現狀）";
            default -> goal;
        };
    }

    // === 趨勢圖 ===============================================================
    // 三項量測（體重／體脂率／骨骼肌重）各畫成一張獨立的折線圖，因為單位不同
    // （kg / % / kg）沒辦法共用一個 y 軸；只有至少兩筆非 null 資料的量測項目才會
    // 畫，避免只有一個點時線段退化成一個點看起來像畫錯。每張圖是獨立的 Image
    // 各自帶 x 軸日期，頁面剩餘空間放得下幾張就排幾張，放不下的才換頁，
    // 不會因為合併成一張大圖整塊被推到下一頁而留下大段空白。
    // 座標軸與缺值斷線的繪製規則已抽至 common.pdf.PdfLineChart（與營運報表共用）。

    private static final float CHART_ROW_HEIGHT = PdfLineChart.CHART_ROW_HEIGHT;

    private List<Image> buildTrendCharts(PdfWriter writer, BaseFont baseFont, List<InbodyRecord> history)
            throws Exception {
        List<Image> charts = new ArrayList<>();
        List<LocalDate> dates = history.stream().map(InbodyRecord::getUpdateDate).toList();
        for (Series series : TREND_SERIES) {
            List<BigDecimal> values = history.stream().map(series.extractor()).toList();
            if (values.stream().filter(v -> v != null).count() < 2) {
                continue;
            }
            charts.add(PdfLineChart.buildChart(writer, baseFont, dates, values, series.label(), series.color()));
        }
        return charts;
    }
}
