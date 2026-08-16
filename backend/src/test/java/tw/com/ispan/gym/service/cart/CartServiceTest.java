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
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.exception.product.ProductNotFoundException;
import tw.com.ispan.gym.exception.product.ProductUnavailableException;
import tw.com.ispan.gym.repository.cart.CartItemRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.product.ProductRepository;

/**
 * 純 Mockito 單元測試（沿用 MemberRegisterServiceTest 的做法：專案無內嵌測試資料庫，
 * 不用 @SpringBootTest，直接 mock Repository）。
 *
 * 涵蓋：小計／總額計算、加入購物車的既有項目累加、下架與庫存檢核的先後、
 * 數量邊界（null／0／負數／溢位）、他人購物車項目的越權存取。
 *
 * 併發只驗證「有沒有呼叫 findByIdForUpdate 取鎖」，不模擬真正的多執行緒競爭——
 * 沒有內嵌測試資料庫可以驗證真實的悲觀鎖行為，這裡只能確認程式碼有走到取鎖這一步。
 */
class CartServiceTest {

    private static final Integer MEMBER_ID = 1;
    private static final Integer OTHER_MEMBER_ID = 2;
    private static final Integer PRODUCT_ID = 100;
    private static final Long CART_ITEM_ID = 500L;

    private final CartItemRepository cartItems = mock(CartItemRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final CartService service = new CartService(cartItems, products, members);

    // === 共用測試資料 ========================================================

    private Member member(Integer id) {
        Member member = new Member();
        member.setId(id);
        return member;
    }

    private Product product(int stock, ProductStatus status, String price) {
        Product product = new Product();
        product.setProductId(PRODUCT_ID);
        product.setName("乳清蛋白");
        product.setImageUrl("/img/whey.png");
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setStatus(status);
        return product;
    }

    private Product availableProduct() {
        return product(10, ProductStatus.ACTIVE, "1000.00");
    }

    private CartItem cartItem(Member owner, Product product, long quantity) {
        return new CartItem(owner, product, quantity);
    }

    private void stubMemberLock() {
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));
    }

    private void stubSaveReturnsArgument() {
        when(cartItems.save(any(CartItem.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // === getCart() ===========================================================

    @Test
    void 取得購物車回傳各項小計與加總總額() {
        Product whey = availableProduct(); // 單價 1000
        Product shaker = product(5, ProductStatus.ACTIVE, "250.50");
        shaker.setProductId(101);
        when(cartItems.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of(
                cartItem(member(MEMBER_ID), whey, 2L),
                cartItem(member(MEMBER_ID), shaker, 3L)));

        CartResponse response = service.getCart(MEMBER_ID);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).subtotal()).isEqualByComparingTo("2000.00");
        assertThat(response.items().get(1).subtotal()).isEqualByComparingTo("751.50");
        // 總額＝各項小計相加，不是重新用單價乘總數量
        assertThat(response.totalAmount()).isEqualByComparingTo("2751.50");
    }

    @Test
    void 空購物車回傳空清單與零元不是null() {
        when(cartItems.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());

        CartResponse response = service.getCart(MEMBER_ID);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // === addItem()：新增與累加 ================================================

    @Test
    void 加入新商品建立項目並回傳小計() {
        stubMemberLock();
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(availableProduct()));
        when(cartItems.findByMemberIdAndProductProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        CartItemResponse response = service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 2L));

        assertThat(response.quantity()).isEqualTo(2L);
        assertThat(response.subtotal()).isEqualByComparingTo("2000.00");
        // 併發防護：確實有對會員取鎖，序列化同一購物車的並行加入
        verify(members).findByIdForUpdate(MEMBER_ID);
    }

    @Test
    void 加入已在購物車的商品累加數量而不是新增第二筆() {
        stubMemberLock();
        Product product = availableProduct();
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        CartItem existing = cartItem(member(MEMBER_ID), product, 3L);
        when(cartItems.findByMemberIdAndProductProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        CartItemResponse response = service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 4L));

        assertThat(response.quantity()).isEqualTo(7L);
        // 存回的是同一筆既有項目，不是新物件
        verify(cartItems).save(existing);
    }

    @Test
    void 累加後超過庫存回庫存不足並以累加後總量比對() {
        stubMemberLock();
        Product product = product(10, ProductStatus.ACTIVE, "1000.00");
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        // 已有 8 件，再加 5 件＝13 件 > 庫存 10；單看新加的 5 件則不會超標
        when(cartItems.findByMemberIdAndProductProductId(MEMBER_ID, PRODUCT_ID))
                .thenReturn(Optional.of(cartItem(member(MEMBER_ID), product, 8L)));

        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 5L)))
                .isInstanceOf(InsufficientStockException.class);

        verify(cartItems, never()).save(any());
    }

    @Test
    void 數量剛好等於庫存可以加入() {
        stubMemberLock();
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product(10, ProductStatus.ACTIVE, "1000.00")));
        when(cartItems.findByMemberIdAndProductProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        CartItemResponse response = service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 10L));

        assertThat(response.quantity()).isEqualTo(10L);
    }

    @Test
    void 累加溢位轉為數量錯誤而不是拋ArithmeticException() {
        stubMemberLock();
        Product product = availableProduct();
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(cartItems.findByMemberIdAndProductProductId(MEMBER_ID, PRODUCT_ID))
                .thenReturn(Optional.of(cartItem(member(MEMBER_ID), product, Long.MAX_VALUE)));

        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 1L)))
                .isInstanceOf(InvalidQuantityException.class);
    }

    // === addItem()：前置檢核 ==================================================

    @Test
    void 數量為零或負數在任何查詢之前就擋下() {
        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 0L)))
                .isInstanceOf(InvalidQuantityException.class);
        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, -1L)))
                .isInstanceOf(InvalidQuantityException.class);

        // 連鎖都沒取，代表是最前面的關卡
        verify(members, never()).findByIdForUpdate(any());
        verify(products, never()).findById(any());
    }

    @Test
    void 數量為null擋下不噴NPE() {
        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, null)))
                .isInstanceOf(InvalidQuantityException.class);
    }

    @Test
    void 會員不存在回404() {
        when(members.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 1L)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(products, never()).findById(any());
    }

    @Test
    void 商品不存在回404() {
        stubMemberLock();
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 1L)))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void 下架商品不可加入且在庫存檢查之前就擋下() {
        stubMemberLock();
        // 庫存充足但已下架：確認擋下的原因是狀態而不是庫存
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product(999, ProductStatus.INACTIVE, "1000.00")));

        assertThatThrownBy(() -> service.addItem(MEMBER_ID, new AddCartItemRequest(PRODUCT_ID, 1L)))
                .isInstanceOf(ProductUnavailableException.class);

        verify(cartItems, never()).findByMemberIdAndProductProductId(any(), any());
        verify(cartItems, never()).save(any());
    }

    // === updateQuantity() ====================================================

    @Test
    void 修改數量成功並重算小計() {
        Product product = availableProduct();
        CartItem item = cartItem(member(MEMBER_ID), product, 2L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(item));

        CartItemResponse response = service.updateQuantity(MEMBER_ID, CART_ITEM_ID, new UpdateCartItemRequest(5L));

        assertThat(response.quantity()).isEqualTo(5L);
        assertThat(response.subtotal()).isEqualByComparingTo("5000.00");
    }

    @Test
    void 修改數量超過庫存回庫存不足且不改動原本數量() {
        Product product = product(10, ProductStatus.ACTIVE, "1000.00");
        CartItem item = cartItem(member(MEMBER_ID), product, 2L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(item));

        assertThatThrownBy(
                () -> service.updateQuantity(MEMBER_ID, CART_ITEM_ID, new UpdateCartItemRequest(11L)))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(item.getQuantity()).isEqualTo(2L);
    }

    @Test
    void 修改數量時商品已下架也擋下() {
        Product product = product(99, ProductStatus.INACTIVE, "1000.00");
        CartItem item = cartItem(member(MEMBER_ID), product, 2L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(item));

        assertThatThrownBy(
                () -> service.updateQuantity(MEMBER_ID, CART_ITEM_ID, new UpdateCartItemRequest(3L)))
                .isInstanceOf(ProductUnavailableException.class);
    }

    @Test
    void 修改數量為零擋下() {
        assertThatThrownBy(
                () -> service.updateQuantity(MEMBER_ID, CART_ITEM_ID, new UpdateCartItemRequest(0L)))
                .isInstanceOf(InvalidQuantityException.class);

        verify(cartItems, never()).findById(any());
    }

    // === 越權存取：ownedItem() ================================================

    @Test
    void 修改他人購物車項目回403() {
        CartItem othersItem = cartItem(member(OTHER_MEMBER_ID), availableProduct(), 1L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(othersItem));

        assertThatThrownBy(
                () -> service.updateQuantity(MEMBER_ID, CART_ITEM_ID, new UpdateCartItemRequest(3L)))
                .isInstanceOf(ForbiddenCartAccessException.class);

        assertThat(othersItem.getQuantity()).isEqualTo(1L);
    }

    @Test
    void 刪除他人購物車項目回403且不刪除() {
        CartItem othersItem = cartItem(member(OTHER_MEMBER_ID), availableProduct(), 1L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(othersItem));

        assertThatThrownBy(() -> service.removeItem(MEMBER_ID, CART_ITEM_ID))
                .isInstanceOf(ForbiddenCartAccessException.class);

        verify(cartItems, never()).delete(any());
    }

    @Test
    void 項目不存在回404() {
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeItem(MEMBER_ID, CART_ITEM_ID))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    // === removeItem() / clearCart() ==========================================

    @Test
    void 刪除自己的項目成功() {
        CartItem item = cartItem(member(MEMBER_ID), availableProduct(), 1L);
        when(cartItems.findById(CART_ITEM_ID)).thenReturn(Optional.of(item));

        service.removeItem(MEMBER_ID, CART_ITEM_ID);

        verify(cartItems).delete(item);
    }

    @Test
    void 清空購物車只清自己的() {
        service.clearCart(MEMBER_ID);

        verify(cartItems).deleteAllByMemberId(MEMBER_ID);
    }
}
