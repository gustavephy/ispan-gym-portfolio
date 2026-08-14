package tw.com.ispan.gym.entity.report;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.report.ReportType;

/**
 * 營運報表快照主檔。
 *
 * 快照語意：生成當下以真實訂單算出的結算結果，寫入後即凍結——
 * 之後訂單被退款/取消不回頭改快照；需要修正時整份重算
 * （同一交易內刪舊插新，見 OperationReportService.generate）。
 *
 * 冪等由 UX_report_type_period 保證：同型別同區間永遠只有一份，
 * 排程重跑或兩位管理員同時觸發都不會產生重複報表。
 *
 * periodStart / periodEnd 為「含頭含尾」的日期區間（對人類友善），
 * 查詢訂單時由 service 轉為 [start 00:00, end+1 00:00) 的半開區間。
 */
@Entity
@Table(name = "report_snapshot",
       uniqueConstraints = @UniqueConstraint(name = "UX_report_type_period",
               columnNames = {"report_type", "period_start", "period_end"}))
@Getter
@EntityListeners(AuditingEntityListener.class)
public class ReportSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 20)
    private ReportType reportType;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** 區間內計入營收的訂單數 */
    @Column(name = "order_count", nullable = false)
    private Long orderCount;

    /** 區間內訂單實收總額（orders.total_amount 加總） */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** 區間內折扣總額 */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    /** 手動生成時記錄操作的員工（原設計指 User，身分域拆分後改指 Employee）；排程生成為 NULL */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by_id", foreignKey = @ForeignKey(name = "FK_report_generated_by"))
    private Employee generatedBy;

    @CreatedDate
    @Column(name = "generated_at", nullable = false, updatable = false, columnDefinition = "datetime2")
    private LocalDateTime generatedAt;

    /**
     * 生成後、涵蓋區間內發生退款的時點；null＝數字仍與訂單一致。
     * 由 RefundCompletedEvent 的 listener 標記，提示管理者重新生成；
     * 重算是刪舊插新，新快照自然回到 null，不需清除邏輯。
     */
    @Column(name = "stale_at", columnDefinition = "datetime2")
    private LocalDateTime staleAt;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("rankNo ASC")
    private List<ReportSalesItem> items = new ArrayList<>();

    protected ReportSnapshot() {}

    public ReportSnapshot(ReportType reportType, LocalDate periodStart, LocalDate periodEnd,
                          Long orderCount, BigDecimal totalAmount, BigDecimal discountAmount,
                          Employee generatedBy) {
        this.reportType = reportType;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.orderCount = orderCount;
        this.totalAmount = totalAmount;
        this.discountAmount = discountAmount;
        this.generatedBy = generatedBy;
    }

    public void addItem(ReportSalesItem item) {
        items.add(item);
    }
}
