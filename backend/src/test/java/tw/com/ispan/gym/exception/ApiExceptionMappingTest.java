package tw.com.ispan.gym.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 固定各例外對應的狀態碼與錯誤代碼。
 *
 * <p>改用 ApiException 父類後，狀態碼由例外自己攜帶而非集中在 handler，
 * 這些斷言用來確保搬移過程沒有改動任何一個對應關係。
 */
class ApiExceptionMappingTest {

    @Test
    void 商品找不到為404() {
        ApiException ex = new ProductNotFoundException(1);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(ex.getMessage()).contains("1");
    }

    @Test
    void 購物車項目找不到為404() {
        ApiException ex = new CartItemNotFoundException(2L);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getCode()).isEqualTo("CART_ITEM_NOT_FOUND");
    }

    @Test
    void 數量不合法為400() {
        ApiException ex = new InvalidQuantityException();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getCode()).isEqualTo("INVALID_QUANTITY");
    }

    @Test
    void 商品無法購買為400() {
        ApiException ex = new ProductUnavailableException(3);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getCode()).isEqualTo("PRODUCT_UNAVAILABLE");
    }

    @Test
    void 庫存不足為409() {
        ApiException ex = new InsufficientStockException(4, 10, 2);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(ex.getMessage()).contains("requested=10").contains("available=2");
    }

    @Test
    void 無權存取購物車為403() {
        ApiException ex = new ForbiddenCartAccessException(5L);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getCode()).isEqualTo("FORBIDDEN_CART_ACCESS");
    }

    @Test
    void 資源找不到為404() {
        ApiException ex = new ResourceNotFoundException("找不到訂單資料");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(ex.getMessage()).isEqualTo("找不到訂單資料");
    }

    @Test
    void 業務規則違反為409() {
        ApiException ex = new BusinessRuleException("此場次已額滿");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getCode()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(ex.getMessage()).isEqualTo("此場次已額滿");
    }

    /** 子類必須都繼承 ApiException，否則會落入 Exception 通用處理變成 500 */
    @Test
    void 所有自訂例外皆為ApiException子類() {
        assertThat(new ProductNotFoundException(1)).isInstanceOf(ApiException.class);
        assertThat(new CartItemNotFoundException(1L)).isInstanceOf(ApiException.class);
        assertThat(new InvalidQuantityException()).isInstanceOf(ApiException.class);
        assertThat(new ProductUnavailableException(1)).isInstanceOf(ApiException.class);
        assertThat(new InsufficientStockException(1, 1, 0)).isInstanceOf(ApiException.class);
        assertThat(new ForbiddenCartAccessException(1L)).isInstanceOf(ApiException.class);
        assertThat(new ResourceNotFoundException("x")).isInstanceOf(ApiException.class);
        assertThat(new BusinessRuleException("x")).isInstanceOf(ApiException.class);
    }
}