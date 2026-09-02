package tw.com.ispan.gym.service.report;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tw.com.ispan.gym.dto.report.GenerateReportRequest;
import tw.com.ispan.gym.enums.report.ReportType;
import tw.com.ispan.gym.repository.report.ReportSnapshotRepository;

/**
 * 每日 00:10（台北時間）自動結算昨日日報。
 * 留 10 分鐘緩衝給跨日付款回調；generate 具冪等性，重跑安全。
 * 失敗只記 log 不重試——後台有手動生成入口可補跑。
 *
 * <p>啟動補跑：排程長在 app 裡的先天弱點是「排程時刻剛好落在停機窗口就漏一天」
 * （半夜當機、清晨才重佈署）。啟動時檢查昨天的日報存不存在，缺了就補生，
 * 把最常見的缺口自動接住；更早的缺口仍走後台手動生成。
 */
@Component
public class DailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReportScheduler.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final OperationReportService reportService;
    private final ReportSnapshotRepository snapshots;

    public DailyReportScheduler(OperationReportService reportService, ReportSnapshotRepository snapshots) {
        this.reportService = reportService;
        this.snapshots = snapshots;
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Taipei")
    public void generateYesterdayDailyReport() {
        generateDaily(LocalDate.now(TAIPEI).minusDays(1), "日報結算");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillYesterdayOnStartup() {
        LocalDate yesterday = LocalDate.now(TAIPEI).minusDays(1);
        if (snapshots.findByReportTypeAndPeriodStartAndPeriodEndAndSupersededAtIsNull(
                ReportType.DAILY, yesterday, yesterday).isPresent()) {
            return;
        }
        log.info("啟動補跑：{} 的日報不存在，自動補生", yesterday);
        generateDaily(yesterday, "啟動補跑");
    }

    private void generateDaily(LocalDate date, String cause) {
        try {
            reportService.generate(new GenerateReportRequest(ReportType.DAILY, date, date), null);
            log.info("{}完成：{}", cause, date);
        } catch (Exception ex) {
            log.error("{}失敗：{}，可由後台手動補跑", cause, date, ex);
        }
    }
}
