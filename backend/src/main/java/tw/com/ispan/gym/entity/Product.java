package tw.com.ispan.gym.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import tw.com.ispan.gym.enums.ProductStatus;
import tw.com.ispan.gym.enums.ProductType;

@Entity
@Table(name = "products")
@Getter
@Setter
@ToString(exclude = {"category"})
@EntityListeners(AuditingEntityListener.class)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ProductType type = ProductType.GOODS;

    @Column(nullable = false, length = 50, columnDefinition = "NVARCHAR(50)")
    private String name;

    @Column(length = 500, columnDefinition = "NVARCHAR(500)")
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Long stock = 0L;

    @Column(nullable = false)
    private Integer sessionsPerUnit = 0;

    @Version
    private Integer version = 0;

    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    @Enumerated(EnumType.STRING)
    private ProductStatus status = ProductStatus.ACTIVE;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String imageUrl;

    @Column(nullable = false, columnDefinition = "bit default 0")
    private Boolean isTrial = false;

    @Column(nullable = false, columnDefinition = "bit default 0")
    private Boolean isOpenForSubscription = false;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(length = 50, columnDefinition = "NVARCHAR(50)")
    private String classSize;

    @CreatedDate
    @Column(updatable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(columnDefinition = "datetime2")
    private LocalDateTime updatedAt;
}
