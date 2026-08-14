package tw.com.ispan.gym.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 開啟 @Scheduled 排程（目前僅 DailyReportScheduler 使用） */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
