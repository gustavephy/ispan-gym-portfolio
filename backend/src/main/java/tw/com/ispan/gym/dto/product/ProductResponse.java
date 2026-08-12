package tw.com.ispan.gym.dto.product;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import tw.com.ispan.gym.entity.Product;

@Data
public class ProductResponse {
    private Integer id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
    private String status;
    private String imageUrl;
    private Integer categoryId;
    private String categoryName;
    private LocalDateTime createdAt;
    private String brand;
    private String flavor;
    private String size;
    private BigDecimal weight;
    private String material;
    private String origin;
    private String color;

    public static ProductResponse from(Product product) {
        ProductResponse dto = new ProductResponse();
        dto.setId(product.getProductId());
        dto.setName(product.getName());
        dto.setDescription(product.getDescription());
        dto.setPrice(product.getPrice());
        dto.setStock(product.getStock());
        dto.setStatus(product.getStatus().name());
        dto.setImageUrl(product.getImageUrl());
        dto.setCategoryId(product.getCategory() != null ? product.getCategory().getProductCategoryId() : null);
        dto.setCategoryName(product.getCategory() != null ? product.getCategory().getName() : null);
        dto.setCreatedAt(product.getCreatedAt());
        dto.setBrand(product.getBrand());
        dto.setFlavor(product.getFlavor());
        dto.setSize(product.getSize());
        dto.setWeight(product.getWeight());
        dto.setMaterial(product.getMaterial());
        dto.setOrigin(product.getOrigin());
        dto.setColor(product.getColor());
        return dto;
    }
}
