package tw.com.ispan.gym.entity.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import tw.com.ispan.gym.enums.report.ReportBreakdownDimension;

/**
 * 報表分佈明細＝「依某維度分組」的固化結果，一列一組。
 * dimension 決定 itemKey 的解讀：STATUS＝OrderStatus 名稱、
 * PAYMENT_METHOD＝orders.payment_method 原值（未付款訂單為 UNSET）。
 * amount 僅 STATUS 維度有值（該狀態訂單金額合計），PAYMENT_METHOD 為 null。
 * 口徑同總覽的訂單分佈：時間軸為下單日 createdAt（看訂單量，非營收歸屬）。
 */
@Entity
@Table(name = "report_breakdown")
@Getter
public class ReportBreakdown {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_snapshot_id", nullable = false,
                foreignKey = @ForeignKey(name = "FK_rbd_snapshot"))
    private ReportSnapshot snapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportBreakdownDimension dimension;

    @Column(name = "item_key", nullable = false, length = 30)
    private String itemKey;

    @Column(name = "order_count", nullable = false)
    private Long orderCount;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    protected ReportBreakdown() {}

    public ReportBreakdown(ReportSnapshot snapshot, ReportBreakdownDimension dimension,
                           String itemKey, Long orderCount, BigDecimal amount) {
        this.snapshot = snapshot;
        this.dimension = dimension;
        this.itemKey = itemKey;
        this.orderCount = orderCount;
        this.amount = amount;
    }
}
