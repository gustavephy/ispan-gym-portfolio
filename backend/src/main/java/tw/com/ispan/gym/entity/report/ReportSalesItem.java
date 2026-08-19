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
import org.hibernate.annotations.Nationalized;
import tw.com.ispan.gym.enums.report.ReportItemType;

/**
 * 報表明細＝品項銷售排行的固化結果，rank_no 生成時排定，讀取免再算。
 *
 * productId 刻意存純數字欄位而非 FK——報表是衍生資料，不應反向
 * 約束主檔（品項被硬刪除時歷史報表仍須完整）。id 依 productType
 * 解讀：PRODUCT＝product_id、COURSE＝private_course_id、
 * SUBSCRIPTION＝subscription_plan_id；顯示一律用 productName 快照。
 */
@Entity
@Table(name = "report_sales_item")
@Getter
public class ReportSalesItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_snapshot_id", nullable = false,
                foreignKey = @ForeignKey(name = "FK_rsi_snapshot"))
    private ReportSnapshot snapshot;

    /** 名次，1 起算，依 grossAmount 降冪 */
    @Column(name = "rank_no", nullable = false)
    private Integer rankNo;

    /** 品項 id（純數字，非 FK；意義依 productType，見類別註解） */
    @Column(name = "product_id", nullable = false)
    private Integer productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 20)
    private ReportItemType productType;

    /** 生成當下的品項名稱快照 */
    @Nationalized
    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "quantity_sold", nullable = false)
    private Long quantitySold;

    /** 該品項在區間內的累計金額（price_at_buy × quantity 加總） */
    @Column(name = "gross_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossAmount;

    protected ReportSalesItem() {}

    public ReportSalesItem(ReportSnapshot snapshot, Integer rankNo, Integer productId,
                           ReportItemType productType, String productName,
                           Long quantitySold, BigDecimal grossAmount) {
        this.snapshot = snapshot;
        this.rankNo = rankNo;
        this.productId = productId;
        this.productType = productType;
        this.productName = productName;
        this.quantitySold = quantitySold;
        this.grossAmount = grossAmount;
    }
}
