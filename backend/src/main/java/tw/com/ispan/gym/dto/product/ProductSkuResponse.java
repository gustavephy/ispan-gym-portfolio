package tw.com.ispan.gym.dto.product;

import lombok.Data;
import tw.com.ispan.gym.entity.ProductSku;

@Data
public class ProductSkuResponse {
    private Integer id;
    private String skuName;
    private Integer stock;
    private String flavor;
    private String size;
    private Double weight;
    private String material;
    private String origin;
    private String color;
    private String skuImageUrl;
    private String brand;
    private String specifications;
    private java.math.BigDecimal price;

    public static ProductSkuResponse from(ProductSku sku) {
        ProductSkuResponse dto = new ProductSkuResponse();
        dto.setId(sku.getId());
        dto.setSkuName(sku.getSkuName());
        dto.setStock(sku.getStock());
        dto.setFlavor(sku.getFlavor());
        dto.setSize(sku.getSize());
        dto.setWeight(sku.getWeight());
        dto.setMaterial(sku.getMaterial());
        dto.setOrigin(sku.getOrigin());
        dto.setColor(sku.getColor());
        dto.setSkuImageUrl(sku.getSkuImageUrl());
        dto.setBrand(sku.getBrand());
        dto.setSpecifications(sku.getSpecifications());
        dto.setPrice(sku.getPrice());
        return dto;
    }
}
