package tw.com.ispan.gym.service.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.cart.AddCartItemRequest;
import tw.com.ispan.gym.dto.cart.CartItemResponse;
import tw.com.ispan.gym.dto.cart.CartResponse;
import tw.com.ispan.gym.dto.cart.UpdateCartItemRequest;
import tw.com.ispan.gym.entity.cart.CartItem;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.enums.product.ProductStatus;
import tw.com.ispan.gym.exception.CartItemNotFoundException;
import tw.com.ispan.gym.exception.ForbiddenCartAccessException;
import tw.com.ispan.gym.exception.InsufficientStockException;
import tw.com.ispan.gym.exception.InvalidQuantityException;
import tw.com.ispan.gym.exception.ProductNotFoundException;
import tw.com.ispan.gym.exception.ProductUnavailableException;
import tw.com.ispan.gym.repository.cart.CartItemRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.product.ProductRepository;

/**
 * 純 Mockito 單元測試（沿用 CoachBookingServiceTest 的做法）。
 * 涵蓋購物車加入/改數量/刪除/清空的守衛規則：庫存、上架狀態、本人歸屬檢查、
 * 同商品重複加入時的數量累加。
 */
class CartServiceTest {

    private static final Integer MEMBER_ID = 1;
    private static final Integer OTHER_MEMBER_ID = 2;
    private static final Integer PRODUCT_ID = 100;
    private static final Long CART_ITEM_ID = 500L;

    private final CartItemRepository cartItems = mock(CartItemRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final MemberRepository users = mock(MemberRepository.class);

    private final CartService service = new CartService(cartItems, products, users);

    private Member member(Integer id) {
        Member member = new Member();
        member.setId(id);
        return member;
    }

    private Product product(int stock, ProductStatus status) {
        Product product = new Product();
        product.setProductId(PRODUCT_ID);
        product.setName("乳清蛋白");
        product.setPrice(BigDecimal.valueOf(1000));
        product.setStock(stock);
        product.setStatus(status);
        return product;
    }

    private CartItem cartItem(Integer ownerId, Product product, long quantity) {
        CartItem item = new CartItem(member(ownerId), product, quantity);
        return item;
    }

    private void stubSaveReturnsArgument() {
        when(cartItems.save(any(CartItem.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // === addItem =============================================================

    @Test
    void 加入購物車_新商品成功加入() {
        Product product = product(10, ProductStatus.ACTIVE);
        when(users.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(cartItems.findByMemberIdAndProductProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        CartItemResponse response = service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 3L));

        assertThat(response.quantity()).isEqualTo(3L);
        assertThat(response.subtotal()).isEqualByComparingTo(BigDecimal.valueOf(3000));
        verify(cartItems).save(any(CartItem.class));
    }

    @Test
    void 加入購物車_已有同商品時數量累加而非新增一筆() {
        Product product = product(10, ProductStatus.ACTIVE);
        CartItem existing = cartItem(MEMBER_ID, product, 2L);
        when(users.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(cartItems.findByMemberIdAndProductProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        CartItemResponse response = service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 3L));

        assertThat(response.quantity()).isEqualTo(5L);
        verify(cartItems).save(existing);
    }

    @Test
    void 加入購物車_數量小於1拋例外() {
        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 0L)))
                .isInstanceOf(InvalidQuantityException.class);
        verify(cartItems, never()).save(any());
    }

    @Test
    void 加入購物車_商品不存在拋例外() {
        when(users.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 1L)))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void 加入購物車_商品未上架拋例外() {
        Product product = product(10, ProductStatus.INACTIVE);
        when(users.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 1L)))
                .isInstanceOf(ProductUnavailableException.class);
        verify(cartItems, never()).save(any());
    }

    @Test
    void 加入購物車_庫存不足拋例外() {
        Product product = product(2, ProductStatus.ACTIVE);
        when(users.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(cartItems.findByMemberIdAndProductProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 3L)))
                .isInstanceOf(InsufficientStockException.class);
        verify(cartItems, never()).save(any());
    }

    // === updateQuantity =======================================================

    @Test
    void 改數量_成功更新() {
        Product product = product(10, ProductStatus.ACTIVE);
        CartItem item = cartItem(MEMBER_ID, product, 1L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(item));

        CartItemResponse response = service.updateQuantity(MEMBER_ID, CART_ITEM_ID, new UpdateCartItemRequest(5L));

        assertThat(response.quantity()).isEqualTo(5L);
    }

    @Test
    void 改數量_非本人項目拒絕() {
        Product product = product(10, ProductStatus.ACTIVE);
        CartItem item = cartItem(OTHER_MEMBER_ID, product, 1L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.updateQuantity(MEMBER_ID, CART_ITEM_ID, new UpdateCartItemRequest(5L)))
                .isInstanceOf(ForbiddenCartAccessException.class);
    }

    @Test
    void 改數量_查無項目回404() {
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateQuantity(MEMBER_ID, CART_ITEM_ID, new UpdateCartItemRequest(5L)))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    void 改數量_超過庫存拒絕() {
        Product product = product(3, ProductStatus.ACTIVE);
        CartItem item = cartItem(MEMBER_ID, product, 1L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.updateQuantity(MEMBER_ID, CART_ITEM_ID, new UpdateCartItemRequest(10L)))
                .isInstanceOf(InsufficientStockException.class);
    }

    // === removeItem／clearCart ================================================

    @Test
    void 刪除項目_非本人拒絕且不刪除() {
        Product product = product(10, ProductStatus.ACTIVE);
        CartItem item = cartItem(OTHER_MEMBER_ID, product, 1L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.removeItem(MEMBER_ID, CART_ITEM_ID))
                .isInstanceOf(ForbiddenCartAccessException.class);
        verify(cartItems, never()).delete(any());
    }

    @Test
    void 刪除項目_本人成功刪除() {
        Product product = product(10, ProductStatus.ACTIVE);
        CartItem item = cartItem(MEMBER_ID, product, 1L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(item));

        service.removeItem(MEMBER_ID, CART_ITEM_ID);

        verify(cartItems).delete(item);
    }

    @Test
    void 清空購物車_呼叫刪除全部() {
        service.clearCart(MEMBER_ID);

        verify(cartItems).deleteAllByMemberId(MEMBER_ID);
    }

    // === getCart ==============================================================

    @Test
    void 查詢購物車_加總正確() {
        Product a = product(10, ProductStatus.ACTIVE);
        a.setProductId(1);
        a.setPrice(BigDecimal.valueOf(100));
        Product b = product(10, ProductStatus.ACTIVE);
        b.setProductId(2);
        b.setPrice(BigDecimal.valueOf(250));
        when(cartItems.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID))
                .thenReturn(List.of(cartItem(MEMBER_ID, a, 2L), cartItem(MEMBER_ID, b, 1L)));

        CartResponse response = service.getCart(MEMBER_ID);

        assertThat(response.items()).hasSize(2);
        assertThat(response.totalAmount()).isEqualByComparingTo(BigDecimal.valueOf(450));
    }
}
