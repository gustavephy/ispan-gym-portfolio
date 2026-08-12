package tw.com.ispan.gym.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import tw.com.ispan.gym.enums.ProductStatus;
import tw.com.ispan.gym.enums.ProductType;

@Entity
@Table(name = "products")
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Integer productId;

    @Column(nullable = false, columnDefinition = "NVARCHAR(50)")
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
    
    @Column(length = 500, columnDefinition = "NVARCHAR(500)")
    private String description;
    
    @Column(nullable=false)
    private Integer stock;

    @Column(nullable = false)
    private Integer sessionsPerUnit = 0;

    @Version
    private Integer version = 0;

    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    @Enumerated(EnumType.STRING)
    private ProductStatus status = ProductStatus.ACTIVE;

    // 商品/課程分流依據；待課程域拆為獨立資料表後再移除
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    @Enumerated(EnumType.STRING)
    private ProductType type = ProductType.GOODS;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String imageUrl;

    // SKU 層移除後，規格欄位攤平至商品主檔
    @Column(length = 100, columnDefinition = "NVARCHAR(100)")
    private String brand;

    @Column(length = 100, columnDefinition = "NVARCHAR(100)")
    private String flavor;

    @Column(length = 50, columnDefinition = "NVARCHAR(50)")
    private String size;

    @Column(precision = 10, scale = 2)
    private BigDecimal weight;

    @Column(length = 100, columnDefinition = "NVARCHAR(100)")
    private String material;

    @Column(length = 100, columnDefinition = "NVARCHAR(100)")
    private String origin;

    @Column(length = 50, columnDefinition = "NVARCHAR(50)")
    private String color;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private ProductCategory category;

    @CreatedDate
    @Column(updatable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(columnDefinition = "datetime2")
    private LocalDateTime updatedAt;
}
