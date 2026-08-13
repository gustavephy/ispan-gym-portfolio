package tw.com.ispan.gym.entity.order;

import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.course.PrivateCourse;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 訂單明細——全系統唯一的雙 FK 多型點。
 *
 * <p>product_id 與 private_course_id 恰好一個非空，履約流程據此分流：
 * 商品扣庫存、課程發放堂數額度。互斥性由 DB CHECK 約束把關
 * （CK_oi_exactly_one_ref，見 SchemaFixupRunner），JPA 無法表達。
 */
@Entity
@Table(name = "order_items")
@EntityListeners(AuditingEntityListener.class)
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "FK_oi_order"))
    private Order order;
    /** 買商品時有值，買課程時為 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", foreignKey = @ForeignKey(name = "FK_oi_product"))
    private Product product;
    /** 買私教課時有值，買商品時為 null（團課不走購買，憑訂閱參加） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "private_course_id", foreignKey = @ForeignKey(name = "FK_oi_private_course"))
    private PrivateCourse course;
    /** 購買當下的品項名稱快照；商品名或課程名 */
    @Nationalized
    @Column(name = "product_name_at_buy", nullable = false, length = 50)
    private String productNameAtBuy;
    @Column(name = "price_at_buy", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtBuy;
    @Column(name = "quantity", nullable = false)
    private Long quantity;
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected OrderItem() {}

    /** 商品明細 */
    public OrderItem(Order order, Product product, String productNameAtBuy, BigDecimal priceAtBuy, Long quantity) {
        this.order = order;
        this.product = product;
        this.productNameAtBuy = productNameAtBuy;
        this.priceAtBuy = priceAtBuy;
        this.quantity = quantity;
    }

    /** 私教課明細 */
    public OrderItem(Order order, PrivateCourse course, String courseNameAtBuy, BigDecimal priceAtBuy, Long quantity) {
        this.order = order;
        this.course = course;
        this.productNameAtBuy = courseNameAtBuy;
        this.priceAtBuy = priceAtBuy;
        this.quantity = quantity;
    }

    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public Product getProduct() { return product; }
    public PrivateCourse getCourse() { return course; }
    public String getProductNameAtBuy() { return productNameAtBuy; }
    public BigDecimal getPriceAtBuy() { return priceAtBuy; }
    public Long getQuantity() { return quantity; }

    /** 本筆明細是否為課程（決定履約走發放額度而非扣庫存） */
    public boolean isCourseItem() { return course != null; }
}
