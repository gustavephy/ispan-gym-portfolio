package tw.com.ispan.gym.service.report;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tw.com.ispan.gym.dto.report.GenerateReportRequest;
import tw.com.ispan.gym.enums.report.ReportType;

/**
 * 每日 00:10（台北時間）自動結算昨日日報。
 * 留 10 分鐘緩衝給跨日付款回調；generate 具冪等性，重跑安全。
 * 失敗只記 log 不重試——後台有手動生成入口可補跑。
 */
@Component
public class DailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReportScheduler.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final OperationReportService reportService;

    public DailyReportScheduler(OperationReportService reportService) {
        this.reportService = reportService;
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Taipei")
    public void generateYesterdayDailyReport() {
        LocalDate yesterday = LocalDate.now(TAIPEI).minusDays(1);
        try {
            reportService.generate(
                    new GenerateReportRequest(ReportType.DAILY, yesterday, yesterday), null);
            log.info("日報結算完成：{}", yesterday);
        } catch (Exception ex) {
            log.error("日報結算失敗：{}，可由後台手動補跑", yesterday, ex);
        }
    }
}
