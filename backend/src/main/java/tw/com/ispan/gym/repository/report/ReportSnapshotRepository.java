package tw.com.ispan.gym.repository.report;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tw.com.ispan.gym.entity.report.ReportSnapshot;
import tw.com.ispan.gym.enums.report.ReportType;

public interface ReportSnapshotRepository extends JpaRepository<ReportSnapshot, Integer> {

    List<ReportSnapshot> findAllByOrderByGeneratedAtDesc();

    Optional<ReportSnapshot> findByReportTypeAndPeriodStartAndPeriodEnd(
            ReportType reportType, LocalDate periodStart, LocalDate periodEnd);
}
