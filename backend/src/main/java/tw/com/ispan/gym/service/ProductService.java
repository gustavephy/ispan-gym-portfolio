package tw.com.ispan.gym.service;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.product.ProductRequest;
import tw.com.ispan.gym.dto.product.ProductResponse;
import tw.com.ispan.gym.entity.Product;
import tw.com.ispan.gym.entity.ProductCategory;
import tw.com.ispan.gym.enums.ProductStatus;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.ProductCategoryRepository;
import tw.com.ispan.gym.repository.ProductRepository;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ProductService {
	
	private final ProductRepository productRepository;
	private final ProductCategoryRepository productCategoryRepository;
	
	//查詢上架商品(分頁，支援分類與關鍵字查詢)
	public Page<ProductResponse> getProducts(Integer productCategoryId, String keyword, String brand, String flavor, String size, BigDecimal minPrice, BigDecimal maxPrice, Integer page, Integer pageSize) {
		PageRequest pageRequest = PageRequest.of(page, pageSize, Sort.by("createdAt").descending());
		
		Page<Product> products = productRepository.findProductsWithFilters(
				ProductStatus.ACTIVE,
				productCategoryId,
				(keyword!= null && !keyword.isBlank())?keyword.trim():null,
				brand,
				flavor,
				size,
				minPrice,
				maxPrice,
				pageRequest
		);
		return products.map(ProductResponse::from);
	}
	
	//查詢單一商品
	public ProductResponse getProductById(Integer productId) {
		Product product = productRepository.findById(productId)
				.orElseThrow(() -> new ResourceNotFoundException("商品不存在"));
		return ProductResponse.from(product);
	}
	
	//新增商品
	public ProductResponse createProduct(ProductRequest productRequest) {
		Product product = new Product();
		product.setName(productRequest.getName());
		product.setDescription(productRequest.getDescription());
		product.setImageUrl(productRequest.getImageUrl());
		product.setPrice(productRequest.getPrice());
		product.setStock(productRequest.getStock());
		product.setBrand(productRequest.getBrand());
		product.setWeight(productRequest.getWeight());
        product.setFlavor(productRequest.getFlavor());
        product.setSize(productRequest.getSize());
        product.setColor(productRequest.getColor());
        product.setMaterial(productRequest.getMaterial());
        product.setOrigin(productRequest.getOrigin());
		
        if(productRequest.getProductCategoryId() != null) {
        	ProductCategory productCategory = productCategoryRepository.findById(productRequest.getProductCategoryId())
					.orElseThrow(() -> new ResourceNotFoundException("商品分類不存在"));
        } else {
			product.setProductCategory(null);
		}
        
        Product savedProduct = productRepository.save(product);
        log.info("Successfully created product with ID: {}", savedProduct.getProductId());
        return ProductResponse.from(savedProduct);
	}
	
	//修改商品（管理員）
    public ProductResponse updateProduct(Integer id, ProductRequest productRequest) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("商品不存在"));
        productRequest.setName(productRequest.getName());
        productRequest.setDescription(productRequest.getDescription());
        productRequest.setImageUrl(productRequest.getImageUrl());
        productRequest.setPrice(productRequest.getPrice() != null ? productRequest.getPrice() : java.math.BigDecimal.ZERO);
        productRequest.setStock(productRequest.getStock() != null ? productRequest.getStock().intValue() : 0);
        productRequest.setBrand(productRequest.getBrand());
        productRequest.setWeight(productRequest.getWeight());
        productRequest.setFlavor(productRequest.getFlavor());
        productRequest.setSize(productRequest.getSize());
        productRequest.setColor(productRequest.getColor());
        productRequest.setMaterial(productRequest.getMaterial());
        productRequest.setOrigin(productRequest.getOrigin());

        if (productRequest.getProductCategoryId() != null) {
            ProductCategory productCategory = productCategoryRepository.findById(productRequest.getProductCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("商品分類不存在"));
            product.setProductCategory(productCategory);
        } else {
            product.setProductCategory(null);
        }

        Product updatedProduct = productRepository.save(product);
        log.info("Successfully updated product: ID={}, Name={}", updatedProduct.getProductId(), updatedProduct.getName());

        ProductResponse responseDto = ProductResponse.from(updatedProduct);
        responseDto.setStock(updatedProduct.getStock());
        responseDto.setPrice(updatedProduct.getPrice());
        return responseDto;
    }

    //下架商品（軟刪除，status 設為 INACTIVE）
    public void deleteProduct(Integer productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new ResourceNotFoundException("商品不存在"));
        product.setStatus(ProductStatus.INACTIVE);
        productRepository.save(product);
        log.info("Successfully soft deleted (deactivated) product: ID={}", productId);
    }
	
}
