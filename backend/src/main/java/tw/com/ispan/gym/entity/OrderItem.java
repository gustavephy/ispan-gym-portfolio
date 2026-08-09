package tw.com.ispan.gym.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Nationalized;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "FK_oi_product"))
    private Product product;
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
    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public String getProductNameAtBuy() { return productNameAtBuy; }
    public BigDecimal getPriceAtBuy() { return priceAtBuy; }
    public Long getQuantity() { return quantity; }
}

