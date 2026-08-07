package com.wpowergym.cart.entity;

import com.wpowergym.common.persistence.TaipeiTimestampEntity;
import com.wpowergym.product.entity.Product;
import com.wpowergym.user.entity.User;
import jakarta.persistence.*;

@Entity
@Table(name = "cart_items", uniqueConstraints = @UniqueConstraint(name = "UX_cart_user_product", columnNames = {"user_id", "product_id"}))
public class CartItem extends TaipeiTimestampEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "FK_cart_user"))
    private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "FK_cart_product"))
    private Product product;
    @Column(name = "quantity", nullable = false)
    private Long quantity;

    protected CartItem() {}
    public CartItem(User user, Product product, long quantity) {
        this.user = user; this.product = product; this.quantity = quantity;
    }
    public void increaseBy(long amount) { this.quantity = Math.addExact(quantity, amount); }
    public void changeQuantity(long quantity) { this.quantity = quantity; }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public Product getProduct() { return product; }
    public Long getQuantity() { return quantity; }
}
