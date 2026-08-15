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

    List<ReportSnapshot> findAllByOrderByGeneratedAtDesc();

    Optional<ReportSnapshot> findByReportTypeAndPeriodStartAndPeriodEnd(
            ReportType reportType, LocalDate periodStart, LocalDate periodEnd);

    /** 退款後標記涵蓋該付款日的快照為過時（日報/月報/自訂區間一體適用）；已標過的不重標 */
    @Modifying
    @Query("""
            UPDATE ReportSnapshot s SET s.staleAt = :now
                WHERE s.periodStart <= :date AND s.periodEnd >= :date AND s.staleAt IS NULL
            """)
    int markStaleCovering(@Param("date") LocalDate date, @Param("now") LocalDateTime now);
}
