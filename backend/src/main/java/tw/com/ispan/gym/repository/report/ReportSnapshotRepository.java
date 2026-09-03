package tw.com.ispan.gym.repository.report;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tw.com.ispan.gym.entity.report.ReportSnapshot;
import tw.com.ispan.gym.enums.report.ReportType;

public interface ReportSnapshotRepository extends JpaRepository<ReportSnapshot, Integer> {

    /** 現行版列表（下拉選單用）；歷史版本走 findAllVersions */
    List<ReportSnapshot> findAllBySupersededAtIsNullOrderByGeneratedAtDesc();

    /** 同型別同區間的現行版；重生成時要被標記 superseded 的就是它 */
    Optional<ReportSnapshot> findByReportTypeAndPeriodStartAndPeriodEndAndSupersededAtIsNull(
            ReportType reportType, LocalDate periodStart, LocalDate periodEnd);

    /** 同型別同區間的全部版本（新的在前，現行版第一筆） */
    List<ReportSnapshot> findAllByReportTypeAndPeriodStartAndPeriodEndOrderByGeneratedAtDesc(
            ReportType reportType, LocalDate periodStart, LocalDate periodEnd);

    /**
     * 退款後標記涵蓋該付款日的快照為過時（日報/月報/自訂區間一體適用）；已標過的不重標。
     * 只標現行版——歷史版本是「當初的數字」，append-only 之後不再變動。
     */
    @Modifying
    @Query("""
            UPDATE ReportSnapshot s SET s.staleAt = :now
                WHERE s.periodStart <= :date AND s.periodEnd >= :date
                  AND s.staleAt IS NULL AND s.supersededAt IS NULL
            """)
    int markStaleCovering(@Param("date") LocalDate date, @Param("now") LocalDateTime now);
}
