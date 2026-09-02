package tw.com.ispan.gym.service.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tw.com.ispan.gym.entity.report.ReportSnapshot;
import tw.com.ispan.gym.enums.report.ReportType;
import tw.com.ispan.gym.repository.report.ReportSnapshotRepository;

/**
 * 啟動補跑的分支邏輯：昨天的日報缺了就補生、已存在就什麼都不做。
 * 排程本體（cron 觸發）不在單元測試範圍。
 */
class DailyReportSchedulerTest {

    private final OperationReportService reportService = mock(OperationReportService.class);
    private final ReportSnapshotRepository snapshots = mock(ReportSnapshotRepository.class);
    private final DailyReportScheduler scheduler = new DailyReportScheduler(reportService, snapshots);

    private static final LocalDate YESTERDAY = LocalDate.now(ZoneId.of("Asia/Taipei")).minusDays(1);

    @Test
    void 啟動時昨日日報不存在就補生() {
        when(snapshots.findByReportTypeAndPeriodStartAndPeriodEndAndSupersededAtIsNull(ReportType.DAILY, YESTERDAY, YESTERDAY))
                .thenReturn(Optional.empty());

        scheduler.backfillYesterdayOnStartup();

        verify(reportService).generate(
                argThatDaily(YESTERDAY), isNull());
    }

    @Test
    void 啟動時昨日日報已存在就不重生() {
        when(snapshots.findByReportTypeAndPeriodStartAndPeriodEndAndSupersededAtIsNull(ReportType.DAILY, YESTERDAY, YESTERDAY))
                .thenReturn(Optional.of(mock(ReportSnapshot.class)));

        scheduler.backfillYesterdayOnStartup();

        verify(reportService, never()).generate(any(), any());
    }

    private static tw.com.ispan.gym.dto.report.GenerateReportRequest argThatDaily(LocalDate date) {
        return org.mockito.ArgumentMatchers.argThat(req -> req != null
                && req.reportType() == ReportType.DAILY
                && date.equals(req.periodStart())
                && date.equals(req.periodEnd()));
    }
}
