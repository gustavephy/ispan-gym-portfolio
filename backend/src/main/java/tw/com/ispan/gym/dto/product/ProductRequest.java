package tw.com.ispan.gym.dto.product;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProductRequest {
	@NotBlank(message="商品名稱不能為空")
	private String name;

	private String description;

	@NotNull(message="價格不得為空")
	@DecimalMin(value="0.01",message="價格必須大於 0")
	private BigDecimal price;

	@NotNull(message="庫存不得為空")
	@Min(value=0,message="庫存不得為負數")
	private Integer stock;

    private String imageUrl;

    private Integer categoryId;

    private String brand;

    private String flavor;

    private String size;

    private BigDecimal weight;

    private String material;

    private String origin;

    private String color;

}
