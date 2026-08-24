package tw.com.ispan.gym.entity.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;

/**
 * 報表每日營收＝趨勢圖資料的固化結果。與總覽同口徑（REVENUE_STATUSES、paid_at 時間軸），
 * 生成時一併寫入，PDF／前端畫趨勢不得於讀取時重算——否則退款發生後
 * 趨勢與總覽會變成兩個口徑，違反快照凍結原則（過時一律靠 staleAt 警示）。
 * 只存有營收的日期；無單日缺列，呈現端自行補零或斷線。
 */
@Entity
@Table(name = "report_daily_total")
@Getter
public class ReportDailyTotal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_snapshot_id", nullable = false,
                foreignKey = @ForeignKey(name = "FK_rdt_snapshot"))
    private ReportSnapshot snapshot;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "order_count", nullable = false)
    private Long orderCount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal revenue;

    protected ReportDailyTotal() {}

    public ReportDailyTotal(ReportSnapshot snapshot, LocalDate statDate, Long orderCount, BigDecimal revenue) {
        this.snapshot = snapshot;
        this.statDate = statDate;
        this.orderCount = orderCount;
        this.revenue = revenue;
    }
}
