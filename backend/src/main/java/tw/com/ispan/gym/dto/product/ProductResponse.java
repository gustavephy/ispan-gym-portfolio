package tw.com.ispan.gym.dto.product;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import tw.com.ispan.gym.entity.Course;
import tw.com.ispan.gym.entity.Product;
import tw.com.ispan.gym.enums.ProductStatus;
import tw.com.ispan.gym.enums.ProductType;

@Data
public class ProductResponse {
    private Integer id;
    private String type;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
    private String status;
    private String imageUrl;
    private Integer categoryId;
    private String categoryName;
    private LocalDateTime createdAt;
    private String classSize;
    private String trainerName;
    private Boolean isTrial;
    private Integer sessionsPerUnit;
    private String brand;
    private Double weight;
    private String specifications;
    
    public static ProductResponse from(Product product) {
        ProductResponse dto = new ProductResponse();
        dto.setId(product.getId());
        dto.setName(product.getName());
        dto.setDescription(product.getDescription());
        dto.setPrice(product.getPrice());
        dto.setStock(product.getStock());
        dto.setStatus(product.getStatus().name());
        dto.setImageUrl(product.getImageUrl());
        dto.setCategoryId(product.getCategory() != null ? product.getCategory().getId() : null);
        dto.setCategoryName(product.getCategory() != null ? product.getCategory().getName() : null);
        dto.setCreatedAt(product.getCreatedAt());
        dto.setClassSize(product.getClassSize());
        return dto;
    }
    
    public static ProductResponse from(Product product, java.util.List<Course> trainerCourses) {
        ProductResponse dto = from(product);
        if (ProductType.COURSE.equals(product.getType()) && trainerCourses != null) {
            trainerCourses.stream()
                // 多重安全比對防線：ID 比對或名稱模糊比對
                .filter(tc -> (tc.getProductId() != null && tc.getProductId().equals(product.getId()))
                           || (tc.getProduct() != null && tc.getProduct().getId().equals(product.getId()))
                           || product.getName().trim().startsWith(tc.getName().trim())
                           || tc.getName().trim().startsWith(product.getName().trim()))
                .findFirst()
                .ifPresent(tc -> {
                    if (tc.getTrainer() != null) {
                        // 組合教練姓名並寫入 DTO
                        dto.setTrainerName(tc.getTrainer().getLastName() + tc.getTrainer().getFirstName());
                    }
                });
        }
        return dto;
    }
}
