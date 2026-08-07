package tw.com.ispan.gym.entity;

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

import java.math.BigDecimal;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "product_skus")
@Data
@EntityListeners(AuditingEntityListener.class)
public class ProductSku {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 100, columnDefinition="NVARCHAR(100)")
    private String skuName;

    @Column(length = 50, columnDefinition="NVARCHAR(50)")
    private String brand;
    
    @Column(length = 50, columnDefinition="NVARCHAR(50)")
    private String flavor;

    @Column(length = 50, columnDefinition="NVARCHAR(50)")
    private String size;

    private Double weight;

    @Column(length = 50, columnDefinition="NVARCHAR(50)")
    private String material;

    @Column(length = 50, columnDefinition="NVARCHAR(50)")
    private String origin;

    @Column(length = 50, columnDefinition="NVARCHAR(50)")
    private String color;

    @Column(length = 500, columnDefinition="NVARCHAR(500)")
    private String skuImageUrl;

    @Column(nullable = false)
    private Integer stock = 0;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Version
    private Integer version = 0;
}