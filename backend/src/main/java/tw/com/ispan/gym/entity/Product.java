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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

@Entity
@Table(name = "products")
@Data
@EntityListeners(AuditingEntityListener.class)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 20)
    private String type = "GOODS";

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
    private String status = "ACTIVE";

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String imageUrl;

    @Column(nullable = false, columnDefinition = "bit default 0")
    private Boolean isTrial = false;

    @Column(nullable = false, columnDefinition = "bit default 0")
    private Boolean isOpenForSubscription = false;

    @ManyToOne
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
