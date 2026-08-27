package tw.com.ispan.gym.entity.cart;

import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.member.Member;

import tw.com.ispan.gym.common.persistence.TaipeiTimestampEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "cart_item", uniqueConstraints = @UniqueConstraint(name = "UX_cart_member_product", columnNames = {
        "member_id", "product_id" }))
public class CartItem extends TaipeiTimestampEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cart_item_id", nullable = false)
    private Integer id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, foreignKey = @ForeignKey(name = "FK_cart_member"))
    private Member member;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "FK_cart_product"))
    private Product product;
    @Column(name = "quantity", nullable = false)
    private Integer quantity;
    @Column(name = "unit_price_snapshot", precision = 10, scale = 2)
    private BigDecimal unitPriceSnapshot;
    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    protected CartItem() {
    }

    public CartItem(Member member, Product product, int quantity) {
        this.member = member;
        this.product = product;
        this.quantity = quantity;
    }

    public void increaseBy(int amount) {
        this.quantity = Math.addExact(quantity, amount);
    }

    public void changeQuantity(int quantity) {
        this.quantity = quantity;
    }

    public Integer getCartItemId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public Product getProduct() {
        return product;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPriceSnapshot() {
        return unitPriceSnapshot;
    }

    /** 加入購物車當下的價格快照；僅供顯示「加入後是否變價」，結帳仍以商品現價計算 */
    public void setUnitPriceSnapshot(BigDecimal unitPriceSnapshot) {
        this.unitPriceSnapshot = unitPriceSnapshot;
    }

    public Integer getVersion() {
        return version;
    }
}
