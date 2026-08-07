package com.wpowergym.cart.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.wpowergym.cart.dto.AddCartItemRequest;
import com.wpowergym.cart.entity.CartItem;
import com.wpowergym.cart.exception.ForbiddenCartAccessException;
import com.wpowergym.cart.exception.InsufficientStockException;
import com.wpowergym.cart.repository.CartItemRepository;
import com.wpowergym.product.entity.*;
import com.wpowergym.product.repository.ProductRepository;
import com.wpowergym.user.entity.User;
import com.wpowergym.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {
    @Mock CartItemRepository cartItems;
    @Mock ProductRepository products;
    @Mock UserRepository users;
    @Mock User user;
    @Mock Product product;

    private CartService service;

    @BeforeEach
    void setUp() {
        service = new CartService(cartItems, products, users);
    }

    @Test
    void getCartCalculatesSubtotalAndTotalFromProductPrice() {
        CartItem first = new CartItem(user, product, 2);
        Product secondProduct = mock(Product.class);
        CartItem second = new CartItem(user, secondProduct, 3);
        stubProduct(product, 10L, ProductType.COURSE, "Yoga", "yoga.jpg", "100.50", 0L);
        stubProduct(secondProduct, 11L, ProductType.GOODS, "Bottle", "bottle.jpg", "40.00", 10L);
        when(cartItems.findAllByUserIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(first, second));

        var response = service.getCart(1L);

        assertEquals(new BigDecimal("321.00"), response.totalAmount());
        assertEquals(new BigDecimal("201.00"), response.items().getFirst().subtotal());
        assertEquals("yoga.jpg", response.items().getFirst().imageUrl());
    }

    @Test
    void addingSameProductUpdatesExistingQuantity() {
        CartItem existing = new CartItem(user, product, 2);
        stubProduct(product, 10L, ProductType.GOODS, "Bottle", null, "100.00", 10L);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(products.findById(10L)).thenReturn(Optional.of(product));
        when(cartItems.findByUserIdAndProductId(1L, 10L)).thenReturn(Optional.of(existing));
        when(cartItems.save(existing)).thenReturn(existing);

        var response = service.addItem(1L, new AddCartItemRequest(10L, 3L));

        assertEquals(5L, existing.getQuantity());
        assertEquals(new BigDecimal("500.00"), response.subtotal());
        verify(cartItems).save(existing);
    }

    @Test
    void addingSameGoodsChecksCombinedQuantityAgainstStock() {
        CartItem existing = new CartItem(user, product, 4);
        stubProduct(product, 10L, ProductType.GOODS, "Bottle", null, "100.00", 5L);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(products.findById(10L)).thenReturn(Optional.of(product));
        when(cartItems.findByUserIdAndProductId(1L, 10L)).thenReturn(Optional.of(existing));

        assertThrows(InsufficientStockException.class,
                () -> service.addItem(1L, new AddCartItemRequest(10L, 2L)));
        verify(cartItems, never()).save(any());
    }

    @Test
    void cannotRemoveAnotherUsersCartItem() {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(2L);
        CartItem item = new CartItem(owner, product, 1);
        when(cartItems.findById(99L)).thenReturn(Optional.of(item));

        assertThrows(ForbiddenCartAccessException.class, () -> service.removeItem(1L, 99L));
        verify(cartItems, never()).delete(any());
    }

    @Test
    void clearCartDeletesOnlyCurrentUsersRows() {
        service.clearCart(7L);
        verify(cartItems).deleteAllByUserId(7L);
    }

    private void stubProduct(Product target, Long id, ProductType type, String name,
                             String imageUrl, String price, Long stock) {
        lenient().when(target.getId()).thenReturn(id);
        lenient().when(target.getType()).thenReturn(type);
        lenient().when(target.getName()).thenReturn(name);
        lenient().when(target.getImageUrl()).thenReturn(imageUrl);
        lenient().when(target.getPrice()).thenReturn(new BigDecimal(price));
        lenient().when(target.getStock()).thenReturn(stock);
        lenient().when(target.getStatus()).thenReturn(ProductStatus.ACTIVE);
    }
}
