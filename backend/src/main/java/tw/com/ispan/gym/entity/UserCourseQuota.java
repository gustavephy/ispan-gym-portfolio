package tw.com.ispan.gym.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 會員課程包額度
 *
 * 三計數器設計（恆等式：total = reserved + used + remaining）
 * remaining : 還能預約的堂數
 * reserved : 已預約但尚未上課/核銷（訂而未消）
 * used : 已消耗（核銷 or 遲取消/缺席）
 *
 * 事件對應：
 * 訂課 → remaining-1, reserved+1
 * 核銷（上課）→ reserved-1, used+1
 * 準時取消（≥72h）→ reserved-1, remaining+1（立即退還）
 * 遲取消/缺席 → reserved-1, used+1（視同消耗，不退還）
 * 到期沒收 → remaining→0, used+N
 */
@Entity
@Table(name = "user_course_quota")
@Data
public class UserCourseQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    /**
     * 對應的課程商品（products 表，type=COURSE）
     * 以 product FK 取代舊有的 courseName 字串匹配，解決改名後對不上的問題
     * nullable = true 相容舊資料
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = true)
    @JsonIgnore
    private Product product;

    @Column(name = "product_id", insertable = false, updatable = false)
    private Integer productId;

    /** 關聯的訂單明細，用於追蹤額度來源 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = true)
    @JsonIgnore
    private OrderItem orderItem;

    /** 課程顯示名稱（來自 product.name，僅供顯示用） */
    @Column(name = "course_name", nullable = false, length = 100, columnDefinition = "NVARCHAR(100)")
    private String courseName;

    /** 還能預約的堂數 */
    @Column(name = "remaining_course", nullable = false)
    private Integer remainingCourse = 0;

    /** 已預約但尚未核銷的堂數（訂而未消） */
    @Column(name = "reserved_course", nullable = false)
    private Integer reservedCourse = 0;

    /** 已消耗的堂數（核銷 or 遲取消/缺席） */
    @Column(name = "used_course", nullable = false)
    private Integer usedCourse = 0;

    @Column(name = "expiry_date", columnDefinition = "datetime2")
    private LocalDateTime expiryDate;

    /**
     * 樂觀鎖：book/consume/refund 都是 read-modify-write，
     * 並發時若無版本控管會造成 lost update，破壞 total = remaining + reserved + used
     */
    @Version
    private Integer version = 0;

    // ─── 便利方法 ───────────────────────────────────────────────

    /** 訂課：remaining-1, reserved+1 */
    public void book() {
        if (this.remainingCourse <= 0)
            throw new IllegalStateException("課程剩餘額度不足");
        this.remainingCourse--;
        this.reservedCourse++;
    }

    /** 核銷/遲取消：reserved-1, used+1（已消耗，不退還） */
    public void consume() {
        if (this.reservedCourse <= 0)
            throw new IllegalStateException("無預約中的堂數可消耗");
        this.reservedCourse--;
        this.usedCourse++;
    }

    /** 準時取消（≥72h）：reserved-1, remaining+1（退還） */
    public void refund() {
        if (this.reservedCourse <= 0)
            throw new IllegalStateException("無預約中的堂數可退還");
        this.reservedCourse--;
        this.remainingCourse++;
    }

    /** 取得課程包總堂數 */
    public int getTotal() {
        return remainingCourse + reservedCourse + usedCourse;
    }

    /** 動態判斷當前狀態 */
    @Transient
    public String getStatus() {
        if (expiryDate != null && java.time.LocalDateTime.now().isAfter(expiryDate)) {
            return "EXPIRED";
        }
        if (remainingCourse <= 0 && reservedCourse <= 0) {
            return "DEPLETED";
        }
        return "ACTIVE";
    }
}
