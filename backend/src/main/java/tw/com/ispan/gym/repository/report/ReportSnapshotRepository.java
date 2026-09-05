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

    /** 現行版且未作廢（下拉選單預設）；歷史版本走 findAllByReportTypeAndPeriodStartAndPeriodEndOrderByGeneratedAtDesc */
    List<ReportSnapshot> findAllBySupersededAtIsNullAndVoidedAtIsNullOrderByGeneratedAtDesc();

    /** 現行版含已作廢（前端勾「顯示已作廢」時用）；作廢的現行版仍是該區間歷史版本的入口 */
    List<ReportSnapshot> findAllBySupersededAtIsNullOrderByGeneratedAtDesc();

    /** 同型別同區間的現行版（作廢與否都算，作廢的仍占著篩選唯一索引）；重生成時要被標記 superseded 的就是它 */
    Optional<ReportSnapshot> findByReportTypeAndPeriodStartAndPeriodEndAndSupersededAtIsNull(
            ReportType reportType, LocalDate periodStart, LocalDate periodEnd);

    /** 同型別同區間的全部版本（新的在前，現行版第一筆；已作廢的也列，由呈現端標示） */
    List<ReportSnapshot> findAllByReportTypeAndPeriodStartAndPeriodEndOrderByGeneratedAtDesc(
            ReportType reportType, LocalDate periodStart, LocalDate periodEnd);

    /**
     * 退款後標記受影響的快照為過時（日報/月報/自訂區間一體適用）；已標過的不重標。
     * 兩個日期任一落在區間內就算受影響：涵蓋付款日的快照營收欄位失真（該訂單已轉 REFUNDED），
     * 涵蓋退款完成日的快照退款筆數／金額欄位失真（summarizeCompleted 以 completedAt 歸屬）。
     * 區間結束後才生成的快照不會被第二個條件打到；會中的是月初生成的月報、週中生成的自訂區間。
     * 只標現行版且未作廢——歷史版本是「當初的數字」、作廢的快照已不作引用，兩者都不再變動。
     */
    @Modifying
    @Query("""
            UPDATE ReportSnapshot s SET s.staleAt = :now
                WHERE ((s.periodStart <= :paidDate AND s.periodEnd >= :paidDate)
                    OR (s.periodStart <= :completedDate AND s.periodEnd >= :completedDate))
                  AND s.staleAt IS NULL AND s.supersededAt IS NULL AND s.voidedAt IS NULL
            """)
    int markStaleCovering(@Param("paidDate") LocalDate paidDate,
                          @Param("completedDate") LocalDate completedDate,
                          @Param("now") LocalDateTime now);
}
