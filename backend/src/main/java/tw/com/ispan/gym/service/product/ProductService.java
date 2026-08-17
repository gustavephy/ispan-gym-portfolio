package tw.com.ispan.gym.service.product;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.product.ProductRequest;
import tw.com.ispan.gym.dto.product.ProductResponse;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.product.ProductCategory;
import tw.com.ispan.gym.enums.product.ProductStatus;
import tw.com.ispan.gym.exception.product.ProductNotFoundException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.product.ProductCategoryRepository;
import tw.com.ispan.gym.repository.product.ProductRepository;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository products;
    private final ProductCategoryRepository productCategories;

    public ProductService(ProductRepository products, ProductCategoryRepository productCategories) {
        this.products = products;
        this.productCategories = productCategories;
    }

    // 前台商品列表：只回上架中的商品，可依分類／關鍵字／品牌／口味／尺寸／價格區間篩選
    public Page<ProductResponse> getProducts(Integer productCategoryId, String keyword, String brand,
            String flavor, String size, BigDecimal minPrice, BigDecimal maxPrice, int page, int pageSize) {
        Pageable pageable = PageRequest.of(page, pageSize, Sort.by("createdAt").descending());
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        return products.findProductsWithFilters(ProductStatus.ACTIVE, productCategoryId, kw,
                brand, flavor, size, minPrice, maxPrice, pageable)
                .map(ProductResponse::from);
    }

    // 查詢單一商品
    public ProductResponse getProductById(Integer productId) {
        return ProductResponse.from(products.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId)));
    }

    // 新增商品
    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        Product product = new Product();
        applyRequest(product, request);
        return ProductResponse.from(products.save(product));
    }

    // 更新商品
    @Transactional
    public ProductResponse updateProduct(Integer productId, ProductRequest request) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        applyRequest(product, request);
        return ProductResponse.from(product);
    }

    // 下架商品
    @Transactional
    public void deleteProduct(Integer productId) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        product.setStatus(ProductStatus.INACTIVE);
    }

    // 內部使用方法，提供給createProduct和updateProduct使用，不對外開放
    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setImageUrl(request.imageUrl());
        product.setBrand(request.brand());
        product.setFlavor(request.flavor());
        product.setSize(request.size());
        product.setWeight(request.weight());
        product.setMaterial(request.material());
        product.setOrigin(request.origin());
        product.setColor(request.color());
        if (request.productCategoryId() != null) {
            ProductCategory category = productCategories.findById(request.productCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("找不到商品分類：" + request.productCategoryId()));
            product.setProductCategory(category);
        } else {
            product.setProductCategory(null);
        }
    }
}
