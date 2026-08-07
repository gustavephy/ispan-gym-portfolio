package com.wpowergym.product.entity;

import com.wpowergym.common.persistence.TaipeiTimestampEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "products")
public class Product extends TaipeiTimestampEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ProductType type;
    @Column(name = "course_id")
    private Long courseId;
    @Nationalized
    @Column(name = "name", nullable = false, length = 50)
    private String name;
    @Nationalized
    @Column(name = "description", length = 1000)
    private String description;
    @Column(name = "image_url", length = 500)
    private String imageUrl;
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
    @Column(name = "stock", nullable = false)
    private Long stock;
    @Column(name = "sessions_count")
    private Integer sessionsCount;
    @Column(name = "validity_days")
    private Integer validityDays;
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status;

    protected Product() {}
    public Long getId() { return id; }
    public ProductType getType() { return type; }
    public String getName() { return name; }
    public String getImageUrl() { return imageUrl; }
    public BigDecimal getPrice() { return price; }
    public Long getStock() { return stock; }
    public ProductStatus getStatus() { return status; }
}
