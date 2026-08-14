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

    private static final float CHART_WIDTH = 480f;
    private static final float CHART_ROW_HEIGHT = 130f;

    private List<Image> buildTrendCharts(PdfWriter writer, BaseFont baseFont, List<InbodyRecord> history)
            throws Exception {
        List<Image> charts = new ArrayList<>();
        List<LocalDate> dates = history.stream().map(InbodyRecord::getUpdateDate).toList();
        for (Series series : TREND_SERIES) {
            List<BigDecimal> values = history.stream().map(series.extractor()).toList();
            if (values.stream().filter(v -> v != null).count() < 2) {
                continue;
            }
            PdfTemplate template = writer.getDirectContent().createTemplate(CHART_WIDTH, CHART_ROW_HEIGHT);
            drawSeriesChart(template, baseFont, 0f, 0f, CHART_WIDTH, CHART_ROW_HEIGHT,
                    dates, values, series.label(), series.color(), true);

            Image image = Image.getInstance(template);
            image.setAlignment(Element.ALIGN_CENTER);
            image.setSpacingBefore(6);
            image.setSpacingAfter(12);
            charts.add(image);
        }
        return charts;
    }

    private void drawSeriesChart(PdfTemplate cb, BaseFont baseFont, float x, float y, float w, float h,
            List<LocalDate> dates, List<BigDecimal> values, String label, Color color, boolean showXAxis) {
        List<Integer> presentIdx = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != null) {
                presentIdx.add(i);
            }
        }

        BigDecimal min = null;
        BigDecimal max = null;
        for (int idx : presentIdx) {
            BigDecimal v = values.get(idx);
            if (min == null || v.compareTo(min) < 0) {
                min = v;
            }
            if (max == null || v.compareTo(max) > 0) {
                max = v;
            }
        }
        if (min.compareTo(max) == 0) {
            // 全部量測值相同時人為拉開範圍，避免折線畫成貼底的一條水平線
            min = min.subtract(BigDecimal.ONE);
            max = max.add(BigDecimal.ONE);
        }

        float labelAreaLeft = 46f; // 給 y 軸數值標籤留的寬度
        float labelAreaBottom = showXAxis ? 26f : 14f; // 給 x 軸日期標籤留的高度
        float plotX = x + labelAreaLeft;
        float plotY = y + labelAreaBottom;
        float plotW = w - labelAreaLeft - 10f;
        float plotH = h - labelAreaBottom - 20f; // 上方留給圖例文字

        // 圖表外框與 y 軸格線（最小值／中間值／最大值）
        cb.setLineWidth(0.5f);
        cb.setColorStroke(new Color(0xD0, 0xD0, 0xD0));
        cb.rectangle(plotX, plotY, plotW, plotH);
        cb.stroke();

        BigDecimal mid = min.add(max).divide(BigDecimal.valueOf(2));
        drawYTick(cb, baseFont, plotX, plotY, plotW, plotH, min, min, max, "min");
        drawYTick(cb, baseFont, plotX, plotY, plotW, plotH, mid, min, max, "mid");
        drawYTick(cb, baseFont, plotX, plotY, plotW, plotH, max, min, max, "max");

        // 圖例（色塊 + 文字）
        cb.setColorFill(color);
        cb.rectangle(plotX, y + h - 12f, 10f, 10f);
        cb.fill();
        cb.beginText();
        cb.setFontAndSize(baseFont, 9);
        cb.setColorFill(Color.DARK_GRAY);
        cb.showTextAligned(Element.ALIGN_LEFT, label, plotX + 14f, y + h - 10.5f, 0);
        cb.endText();

        // 折線與資料點：只在「有值」的索引之間連線，x 座標仍依整體時間序均分，
        // 缺值月份會自然斷開一段而不是被拉直插值，避免暗示不存在的量測。
        float dx = values.size() > 1 ? plotW / (values.size() - 1) : 0f;
        cb.setLineWidth(1.5f);
        cb.setColorStroke(color);
        boolean penDown = false;
        for (int idx : presentIdx) {
            BigDecimal v = values.get(idx);
            float px = plotX + dx * idx;
            float py = plotY + toRatio(v, min, max) * plotH;
            if (!penDown || !isContiguous(presentIdx, idx)) {
                cb.moveTo(px, py);
                penDown = true;
            } else {
                cb.lineTo(px, py);
            }
            cb.circle(px, py, 1.8f);
        }
        cb.stroke();
        cb.setColorFill(color);
        for (int idx : presentIdx) {
            BigDecimal v = values.get(idx);
            float px = plotX + dx * idx;
            float py = plotY + toRatio(v, min, max) * plotH;
            cb.circle(px, py, 1.8f);
            cb.fill();
        }

        if (showXAxis) {
            drawXLabels(cb, baseFont, plotX, plotY, plotW, dates);
        }
    }

    private boolean isContiguous(List<Integer> presentIdx, int idx) {
        int pos = presentIdx.indexOf(idx);
        return pos > 0 && presentIdx.get(pos - 1) == idx - 1;
    }

    private float toRatio(BigDecimal v, BigDecimal min, BigDecimal max) {
        return v.subtract(min).divide(max.subtract(min), 6, java.math.RoundingMode.HALF_UP).floatValue();
    }

    private void drawYTick(PdfTemplate cb, BaseFont baseFont, float plotX, float plotY, float plotW, float plotH,
            BigDecimal value, BigDecimal min, BigDecimal max, String position) {
        float ratio = toRatio(value, min, max);
        float ty = plotY + ratio * plotH;
        cb.setLineWidth(0.4f);
        cb.setColorStroke(new Color(0xE8, 0xE8, 0xE8));
        cb.moveTo(plotX, ty);
        cb.lineTo(plotX + plotW, ty);
        cb.stroke();

        cb.beginText();
        cb.setFontAndSize(baseFont, 8);
        cb.setColorFill(Color.GRAY);
        cb.showTextAligned(Element.ALIGN_RIGHT, value.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString(),
                plotX - 4f, ty - 3f, 0);
        cb.endText();
    }

    private void drawXLabels(PdfTemplate cb, BaseFont baseFont, float plotX, float plotY, float plotW,
            List<LocalDate> dates) {
        int n = dates.size();
        float dx = n > 1 ? plotW / (n - 1) : 0f;
        // 資料點太多時全部印會互相重疊，只挑頭、尾與中間幾個等距索引來顯示
        int maxLabels = 6;
        int step = Math.max(1, (int) Math.ceil(n / (double) maxLabels));

        cb.setColorFill(Color.GRAY);
        for (int i = 0; i < n; i += step) {
            drawOneXLabel(cb, baseFont, plotX, plotY, dx, dates, i);
        }
        if ((n - 1) % step != 0) {
            drawOneXLabel(cb, baseFont, plotX, plotY, dx, dates, n - 1);
        }
    }

    private void drawOneXLabel(PdfTemplate cb, BaseFont baseFont, float plotX, float plotY, float dx,
            List<LocalDate> dates, int i) {
        String text = dates.get(i).format(DATE_FMT);
        // 標籤以資料點為中心置中，但頭尾資料點貼著繪圖區邊緣，置中會超出
        // template 畫布（超出部分直接被裁掉），所以把中心點夾回可完整顯示的範圍
        float half = baseFont.getWidthPoint(text, 7.5f) / 2;
        float px = Math.min(Math.max(plotX + dx * i, half), CHART_WIDTH - half);
        cb.beginText();
        cb.setFontAndSize(baseFont, 7.5f);
        cb.showTextAligned(Element.ALIGN_CENTER, text, px, plotY - 12f, 0);
        cb.endText();
    }
}
