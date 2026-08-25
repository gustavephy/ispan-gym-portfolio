package tw.com.ispan.gym.service.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import tw.com.ispan.gym.dto.product.ProductRequest;
import tw.com.ispan.gym.dto.product.ProductResponse;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.product.ProductCategory;
import tw.com.ispan.gym.enums.product.ProductStatus;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.exception.product.ProductNotFoundException;
import tw.com.ispan.gym.repository.product.ProductCategoryRepository;
import tw.com.ispan.gym.repository.product.ProductRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 涵蓋：前台列表一律只查 ACTIVE、空白關鍵字正規化為 null（避免當成「搜尋空字串」）、
 * 建立／更新時分類的連結與解除、以及「下架＝改狀態而非實體刪除」這條對訂單明細很重要的規則。
 *
 * 分頁與排序參數只驗證有沒有正確傳給 repository，實際 SQL 行為要靠資料庫驗證。
 */
class ProductServiceTest {

    private static final Integer PRODUCT_ID = 100;
    private static final Integer CATEGORY_ID = 7;

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductCategoryRepository categories = mock(ProductCategoryRepository.class);
    private final ProductService service = new ProductService(products, categories);

    // === 共用測試資料 ========================================================

    private Product product() {
        Product product = new Product();
        product.setProductId(PRODUCT_ID);
        product.setName("乳清蛋白");
        product.setPrice(new BigDecimal("1200"));
        product.setStock(10);
        product.setStatus(ProductStatus.ACTIVE);
        return product;
    }

    private ProductRequest request() {
        return request(null);
    }

    /** record 無 setter，需要分類的測試改用這個多載直接帶 productCategoryId */
    private ProductRequest request(Integer productCategoryId) {
        return new ProductRequest(
                "高蛋白餅乾",          // name
                "低糖高蛋白",          // description
                new BigDecimal("80"), // price
                50,                   // stock
                null,                 // imageUrl
                productCategoryId,
                "MyBrand",            // brand
                null,                 // weight
                "巧克力",              // flavor
                "M",                  // size
                null,                 // color
                null,                 // material
                null);                // origin
    }

    private ProductCategory category() {
        ProductCategory category = new ProductCategory();
        category.setProductCategoryId(CATEGORY_ID);
        category.setName("補給品");
        return category;
    }

    // === getProducts()：前台列表 ==============================================

    @Test
    void 前台列表只查上架中的商品() {
        when(products.findProductsWithFilters(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(product())));

        service.getProducts(null, null, null, null, null, null, null, null, null, null, 0, 10);

        // 第一個參數固定是 ACTIVE，前台看不到下架商品
        verify(products).findProductsWithFilters(eq(ProductStatus.ACTIVE), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void 空白關鍵字正規化為null而不是搜尋空字串() {
        when(products.findProductsWithFilters(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.getProducts(null, "   ", null, null, null, null, null, null, null, null, 0, 10);

        verify(products).findProductsWithFilters(eq(ProductStatus.ACTIVE), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void 關鍵字前後空白會被修掉再查詢() {
        when(products.findProductsWithFilters(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.getProducts(null, "  乳清  ", null, null, null, null, null, null, null, null, 0, 10);

        verify(products).findProductsWithFilters(eq(ProductStatus.ACTIVE), isNull(), eq("乳清"), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void 分頁參數原樣帶入查詢() {
        when(products.findProductsWithFilters(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.getProducts(CATEGORY_ID, null, null, null, null, new BigDecimal("100"), new BigDecimal("500"),
                null, null, null, 2, 20);

        verify(products).findProductsWithFilters(eq(ProductStatus.ACTIVE), eq(CATEGORY_ID), isNull(), isNull(),
                isNull(), isNull(), eq(new BigDecimal("100")), eq(new BigDecimal("500")), isNull(), isNull(),
                org.mockito.ArgumentMatchers.argThat((Pageable p) ->
                        p.getPageNumber() == 2 && p.getPageSize() == 20));
    }

    // === getProductById() ====================================================

    @Test
    void 查單一商品回傳對應欄位() {
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));

        ProductResponse response = service.getProductById(PRODUCT_ID);

        assertThat(response.productId()).isEqualTo(PRODUCT_ID);
        assertThat(response.name()).isEqualTo("乳清蛋白");
    }

    @Test
    void 查不存在的商品回404() {
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProductById(PRODUCT_ID))
                .isInstanceOf(ProductNotFoundException.class);
    }

    // === createProduct() =====================================================

    @Test
    void 建立商品成功寫入各欄位() {
        when(products.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductResponse response = service.createProduct(request());

        assertThat(response.name()).isEqualTo("高蛋白餅乾");
        assertThat(response.price()).isEqualByComparingTo("80");
        assertThat(response.stock()).isEqualTo(50);
        // 新商品預設為上架
        verify(products).save(org.mockito.ArgumentMatchers.argThat(
                (Product p) -> p.getStatus() == ProductStatus.ACTIVE));
    }

    @Test
    void 建立商品時帶分類會連結該分類() {
        ProductRequest request = request(CATEGORY_ID);
        when(categories.findById(CATEGORY_ID)).thenReturn(Optional.of(category()));
        when(products.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createProduct(request);

        verify(products).save(org.mockito.ArgumentMatchers.argThat(
                (Product p) -> p.getProductCategory() != null
                        && p.getProductCategory().getProductCategoryId().equals(CATEGORY_ID)));
    }

    @Test
    void 建立商品時分類不存在回404且不寫入() {
        ProductRequest request = request(CATEGORY_ID);
        when(categories.findById(CATEGORY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(products, never()).save(any());
    }

    // === updateProduct() =====================================================

    @Test
    void 更新商品覆寫欄位() {
        Product existing = product();
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

        ProductResponse response = service.updateProduct(PRODUCT_ID, request());

        assertThat(response.name()).isEqualTo("高蛋白餅乾");
        assertThat(existing.getStock()).isEqualTo(50);
        // 交給 JPA dirty checking，不需要顯式 save
        assertThat(existing.getPrice()).isEqualByComparingTo("80");
    }

    @Test
    void 更新商品時不帶分類會解除原本的分類連結() {
        Product existing = product();
        existing.setProductCategory(category());
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

        service.updateProduct(PRODUCT_ID, request()); // request 沒有 productCategoryId

        assertThat(existing.getProductCategory()).isNull();
    }

    @Test
    void 更新不存在的商品回404() {
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProduct(PRODUCT_ID, request()))
                .isInstanceOf(ProductNotFoundException.class);
    }

    // === deleteProduct()：軟下架 ==============================================

    @Test
    void 下架商品只改狀態不實體刪除() {
        Product existing = product();
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

        service.deleteProduct(PRODUCT_ID);

        assertThat(existing.getStatus()).isEqualTo(ProductStatus.INACTIVE);
        // 訂單明細仍引用這列商品，不能真的刪掉
        verify(products, never()).delete(any());
        verify(products, never()).deleteById(any());
    }

    @Test
    void 下架不存在的商品回404() {
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteProduct(PRODUCT_ID))
                .isInstanceOf(ProductNotFoundException.class);
    }
}
