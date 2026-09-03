package tw.com.ispan.gym.service.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.report.ReportSalesItem;
import tw.com.ispan.gym.entity.report.ReportSnapshot;
import tw.com.ispan.gym.enums.report.ReportItemType;
import tw.com.ispan.gym.enums.report.ReportType;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.report.ReportSnapshotRepository;

/**
 * 比照 PdfReportServiceTest 的驗證方式：PDF 內容不易逐字斷言，成功案例驗證
 * 「回傳合法 PDF 位元組」（%PDF 檔頭與 %%EOF 結尾），重點放在各種快照狀態
 * （無明細、staleAt 過時警示、金額缺值、無產生者）都能走完繪製流程不炸出非預期例外。
 *
 * <p>id／generatedAt／staleAt 由 JPA 生成或 repository 批次 UPDATE 寫入，
 * entity 刻意不提供 setter，測試以 ReflectionTestUtils 填值。
 */
class ReportPdfServiceTest {

    private static final Integer REPORT_ID = 7;

    private final ReportSnapshotRepository snapshots = mock(ReportSnapshotRepository.class);
    private final ReportPdfService service = new ReportPdfService(snapshots);

    private ReportSnapshot snapshot(Employee generatedBy) {
        ReportSnapshot snapshot = new ReportSnapshot(ReportType.MONTHLY,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                42L, new BigDecimal("123456.00"), new BigDecimal("2345.00"), generatedBy);
        ReflectionTestUtils.setField(snapshot, "id", REPORT_ID);
        ReflectionTestUtils.setField(snapshot, "generatedAt", LocalDateTime.of(2026, 8, 17, 10, 0));
        return snapshot;
    }

    private Employee employee() {
        Employee employee = new Employee();
        employee.setFirstName("管理");
        employee.setLastName("測試");
        return employee;
    }

    private void assertIsPdf(byte[] bytes) {
        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String tail = new String(bytes, Math.max(0, bytes.length - 32), Math.min(32, bytes.length),
                StandardCharsets.US_ASCII);
        assertThat(tail).contains("%%EOF");
    }

    @Test
    void 找不到報表回404() {
        when(snapshots.findById(REPORT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateReportPdf(REPORT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 完整快照產出合法PDF() {
        ReportSnapshot snapshot = snapshot(employee());
        snapshot.addItem(new ReportSalesItem(snapshot, 1, 100,
                ReportItemType.PRODUCT, "乳清蛋白", 30L, new BigDecimal("36000.00")));
        snapshot.addItem(new ReportSalesItem(snapshot, 2, 5,
                ReportItemType.COURSE, "一對一私人教練課", 12L, new BigDecimal("30000.00")));
        snapshot.addItem(new ReportSalesItem(snapshot, 3, 1,
                ReportItemType.SUBSCRIPTION, "季訂閱方案", 8L, new BigDecimal("21600.00")));
        when(snapshots.findById(REPORT_ID)).thenReturn(Optional.of(snapshot));

        assertIsPdf(service.generateReportPdf(REPORT_ID));
    }

    @Test
    void 無銷售明細的快照仍產出合法PDF() {
        when(snapshots.findById(REPORT_ID)).thenReturn(Optional.of(snapshot(employee())));

        assertIsPdf(service.generateReportPdf(REPORT_ID));
    }

    @Test
    void 過時快照帶staleAt警示仍產出合法PDF() {
        ReportSnapshot snapshot = snapshot(employee());
        ReflectionTestUtils.setField(snapshot, "staleAt", LocalDateTime.of(2026, 8, 17, 12, 30));
        when(snapshots.findById(REPORT_ID)).thenReturn(Optional.of(snapshot));

        assertIsPdf(service.generateReportPdf(REPORT_ID));
    }

    @Test
    void 已作廢快照帶作廢警示仍產出合法PDF() {
        ReportSnapshot snapshot = snapshot(employee());
        snapshot.markVoided(LocalDateTime.of(2026, 8, 18, 9, 0), employee(), "選錯區間");
        when(snapshots.findById(REPORT_ID)).thenReturn(Optional.of(snapshot));

        assertIsPdf(service.generateReportPdf(REPORT_ID));
    }

    @Test
    void 已作廢但無作廢人與原因的快照仍產出合法PDF() {
        ReportSnapshot snapshot = snapshot(null);
        snapshot.markVoided(LocalDateTime.of(2026, 8, 18, 9, 0), null, null);
        when(snapshots.findById(REPORT_ID)).thenReturn(Optional.of(snapshot));

        assertIsPdf(service.generateReportPdf(REPORT_ID));
    }

    @Test
    void 明細金額缺值與無產生者不影響產出() {
        ReportSnapshot snapshot = snapshot(null);
        snapshot.addItem(new ReportSalesItem(snapshot, 1, 100,
                ReportItemType.PRODUCT, "乳清蛋白", 30L, null));
        when(snapshots.findById(REPORT_ID)).thenReturn(Optional.of(snapshot));

        assertIsPdf(service.generateReportPdf(REPORT_ID));
    }
}
