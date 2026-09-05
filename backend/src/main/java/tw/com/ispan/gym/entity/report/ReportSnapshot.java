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
 * 之後訂單被退款/取消不回頭改快照；需要修正時整份重算。
 *
 * append-only 版本化（2026-09-03 定案）：同型別同區間重新生成不刪舊版，
 * 舊版蓋上 supersededAt 成為歷史版本、新版為現行版（supersededAt IS NULL）。
 * 「當初印出去的那份數字」永遠調得出來——財務對帳的硬需求。
 *
 * 「現行版唯一」由篩選唯一索引 UX_report_type_period_current 保證
 * （UNIQUE CONSTRAINT 帶不了 WHERE，JPA 表達不了，見 SchemaFixupRunner），
 * 排程重跑或兩位管理員同時觸發都不會產生兩份現行版。
 *
 * periodStart / periodEnd 為「含頭含尾」的日期區間（對人類友善），
 * 查詢訂單時由 service 轉為 [start 00:00, end+1 00:00) 的半開區間。
 */
@Entity
@Table(name = "report_snapshot")
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
     * 生成後、有退款影響到區間內數字的時點；null＝數字仍與訂單一致。
     * 退款的付款日落在區間內（營收欄位失真）或退款完成日落在區間內（退款彙總欄位失真）都算，
     * 由 RefundCompletedEvent 的 listener 標記（只標現行版），提示管理者重新生成；
     * 重算後新版自然回到 null，不需清除邏輯。
     */
    @Column(name = "stale_at", columnDefinition = "datetime2")
    private LocalDateTime staleAt;

    /** 被較新版本取代的時間；null＝現行版。歷史版本從此唯讀（stale 標記也不再打上來） */
    @Column(name = "superseded_at", columnDefinition = "datetime2")
    private LocalDateTime supersededAt;

    /** append-only 版本化：被新版取代時蓋上時間戳讓出「現行版」篩選唯一索引，不刪列 */
    public void markSuperseded(LocalDateTime at) {
        this.supersededAt = at;
    }

    /**
     * 作廢時間；null＝有效。快照不提供刪除（2026-09-03 定案）：報表是稽核紀錄，
     * 誤生成（選錯區間、測試多按）只能「作廢」——清單預設隱藏、資料列與明細原封保留，
     * 作廢人與原因一併記錄。現行版與歷史版都可作廢；作廢的現行版仍占著「現行版」
     * 的篩選唯一索引，重生成時照樣被標 superseded 讓位，舊版不會因作廢而「復活」。
     */
    @Column(name = "voided_at", columnDefinition = "datetime2")
    private LocalDateTime voidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voided_by_id", foreignKey = @ForeignKey(name = "FK_report_voided_by"))
    private Employee voidedBy;

    @Column(name = "void_reason", columnDefinition = "nvarchar(200)")
    private String voidReason;

    public boolean isVoided() {
        return voidedAt != null;
    }

    /** 作廢：只蓋時間戳、作廢人與原因，統計欄位與明細一律不動，數字仍可追溯 */
    public void markVoided(LocalDateTime at, Employee by, String reason) {
        this.voidedAt = at;
        this.voidedBy = by;
        this.voidReason = reason;
    }

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

    // === 延伸統計（2026-08-17 擴充；舊快照這些欄位為 null／空集合，呈現端須容忍）====

    /** 期間內完成退款筆數（completed_at 落在區間、status=COMPLETED） */
    @Column(name = "refund_count")
    private Long refundCount;

    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    /** 期間內新註冊會員數（member.created_at） */
    @Column(name = "new_member_count")
    private Long newMemberCount;

    /** 期間內新生效訂閱數（member_subscription.created_at） */
    @Column(name = "new_subscription_count")
    private Long newSubscriptionCount;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("statDate ASC")
    private List<ReportDailyTotal> dailyTotals = new ArrayList<>();

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ReportBreakdown> breakdowns = new ArrayList<>();

    public void addDailyTotal(ReportDailyTotal dailyTotal) {
        dailyTotals.add(dailyTotal);
    }

    public void addBreakdown(ReportBreakdown breakdown) {
        breakdowns.add(breakdown);
    }

    /** 生成時一次附掛延伸統計的純量值；不提供個別 setter，快照寫入後即凍結 */
    public void attachExtendedStats(Long refundCount, BigDecimal refundAmount,
                                    Long newMemberCount, Long newSubscriptionCount) {
        this.refundCount = refundCount;
        this.refundAmount = refundAmount;
        this.newMemberCount = newMemberCount;
        this.newSubscriptionCount = newSubscriptionCount;
    }
}
