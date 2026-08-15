package tw.com.ispan.gym.enums.report;

/** 營運報表類型（report_snapshot.report_type） */
public enum ReportType {
    /** 日報：每日排程自動生成，區間為單一天 */
    DAILY,
    /** 月報：後台手動或排程生成，區間為整月 */
    MONTHLY,
    /** 自訂區間：後台手動生成 */
    CUSTOM
}
