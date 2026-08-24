package tw.com.ispan.gym.common.pdf;

import java.awt.Color;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.openpdf.text.Element;
import org.openpdf.text.Image;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;

/**
 * PDF 折線圖共用繪製，自 aihealthchat.PdfReportService 抽出：
 * InBody 趨勢與營運報表的每日營收共用同一套座標軸、缺值斷線與標籤收斂規則，
 * 兩邊的視覺與行為不再各自漂移。
 *
 * <p>繪製約定（沿用原定案）：
 * - y 軸三條格線（最小／中間／最大值）；全部值相同時人為拉開範圍避免貼底水平線
 * - 缺值（null）處折線自然斷開，不插值——不暗示不存在的量測
 * - x 軸日期標籤最多 6 個，等距抽樣並保證頭尾都有；貼邊標籤夾回畫布內
 */
public final class PdfLineChart {

    public static final float CHART_WIDTH = 480f;
    public static final float CHART_ROW_HEIGHT = 130f;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private PdfLineChart() {
    }

    /**
     * 畫一張折線圖並回傳可直接 add 進 Document 的 Image。
     * dates 與 values 等長、依時間先後排序；values 可含 null（該點斷線）。
     * 呼叫端應先確認至少 2 筆非 null 值，單點折線會退化成一個點。
     */
    public static Image buildChart(PdfWriter writer, BaseFont baseFont, List<LocalDate> dates,
            List<BigDecimal> values, String label, Color color) throws Exception {
        PdfTemplate template = writer.getDirectContent().createTemplate(CHART_WIDTH, CHART_ROW_HEIGHT);
        drawSeriesChart(template, baseFont, 0f, 0f, CHART_WIDTH, CHART_ROW_HEIGHT,
                dates, values, label, color, true);

        Image image = Image.getInstance(template);
        image.setAlignment(Element.ALIGN_CENTER);
        image.setSpacingBefore(6);
        image.setSpacingAfter(12);
        return image;
    }

    private static void drawSeriesChart(PdfTemplate cb, BaseFont baseFont, float x, float y, float w, float h,
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
        drawYTick(cb, baseFont, plotX, plotY, plotW, plotH, min, min, max);
        drawYTick(cb, baseFont, plotX, plotY, plotW, plotH, mid, min, max);
        drawYTick(cb, baseFont, plotX, plotY, plotW, plotH, max, min, max);

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

    private static boolean isContiguous(List<Integer> presentIdx, int idx) {
        int pos = presentIdx.indexOf(idx);
        return pos > 0 && presentIdx.get(pos - 1) == idx - 1;
    }

    private static float toRatio(BigDecimal v, BigDecimal min, BigDecimal max) {
        return v.subtract(min).divide(max.subtract(min), 6, java.math.RoundingMode.HALF_UP).floatValue();
    }

    private static void drawYTick(PdfTemplate cb, BaseFont baseFont, float plotX, float plotY, float plotW,
            float plotH, BigDecimal value, BigDecimal min, BigDecimal max) {
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

    private static void drawXLabels(PdfTemplate cb, BaseFont baseFont, float plotX, float plotY, float plotW,
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

    private static void drawOneXLabel(PdfTemplate cb, BaseFont baseFont, float plotX, float plotY, float dx,
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
