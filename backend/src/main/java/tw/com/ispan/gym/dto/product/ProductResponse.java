package tw.com.ispan.gym.dto.product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;
import tw.com.ispan.gym.entity.Product;
import tw.com.ispan.gym.enums.ProductStatus;

@Data
public class ProductResponse {
    private Integer productId;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
    private ProductStatus status;
    private String imageUrl;
    private Integer categoryId;
    private String categoryName;
    private LocalDateTime createdAt;
    private String brand;
    private Double weight;
    private String flavor;
    private String size;
    private String color;
    private String material;
    private String origin;
    
    public static ProductResponse from(Product product) {
        ProductResponse dto = new ProductResponse();
        dto.setProductId(product.getProductId());
        dto.setName(product.getName());
        dto.setDescription(product.getDescription());
        dto.setPrice(product.getPrice());
        dto.setStock(product.getStock());
        dto.setStatus(product.getStatus());
        dto.setImageUrl(product.getImageUrl());
        dto.setCategoryId(product.getProductCategory() != null ? product.getProductCategory().getProductCategoryId() : null);
        dto.setCategoryName(product.getProductCategory() != null ? product.getProductCategory().getName() : null);
        dto.setCreatedAt(product.getCreatedAt());
        dto.setBrand(product.getBrand());
        dto.setWeight(product.getWeight());
        dto.setFlavor(product.getFlavor());
        dto.setSize(product.getSize());
        dto.setColor(product.getColor());
        dto.setMaterial(product.getMaterial());
        dto.setOrigin(product.getOrigin());
        return dto;
    }
    
}
