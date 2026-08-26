package tw.com.ispan.gym.controller.cart;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.cart.CartItemResponse;
import tw.com.ispan.gym.dto.cart.CartResponse;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.InsufficientStockException;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.cart.CartService;

/**
 * CartController 的 controller 層測試：standalone MockMvc、service 全 mock，
 * 只驗證路由、請求驗證、狀態碼與回應格式。授權由 {@code EndpointAuthorizationTest} 覆蓋。
 */
class CartControllerTest {

    private final CartService cartService = mock(CartService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new CartController(cartService, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private CartItemResponse item() {
        return new CartItemResponse(1, 100, "乳清蛋白", null, new BigDecimal("1000"), 2,
                new BigDecimal("2000"), new BigDecimal("1000"), false, true, 10);
    }

    @Test
    void 查詢購物車回200與總額() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        when(cartService.getCart(1)).thenReturn(new CartResponse(List.of(item()), new BigDecimal("2000")));

        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(2000))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[0].priceChanged").value(false));
    }

    @Test
    void 加入品項成功回201() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        when(cartService.addItem(eq(1), any())).thenReturn(item());

        mockMvc.perform(post("/api/cart/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":100,\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(100));
    }

    @Test
    void 加入品項數量為零時回400並帶欄位錯誤() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);

        mockMvc.perform(post("/api/cart/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":100,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.quantity").exists());
    }

    @Test
    void 加入品項缺productId時回400() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);

        mockMvc.perform(post("/api/cart/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.productId").exists());
    }

    @Test
    void 庫存不足由service丟出時對應到409() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        when(cartService.addItem(eq(1), any())).thenThrow(new InsufficientStockException(100, 5, 3));

        mockMvc.perform(post("/api/cart/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":100,\"quantity\":5}"))
                .andExpect(status().isConflict());
    }

    @Test
    void 更新數量回200() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        when(cartService.updateQuantity(eq(1), eq(1), any())).thenReturn(item());

        mockMvc.perform(put("/api/cart/items/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
                .andExpect(status().isOk());
    }

    @Test
    void 移除品項回204() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);

        mockMvc.perform(delete("/api/cart/items/1")).andExpect(status().isNoContent());
        verify(cartService).removeItem(1, 1);
    }

    @Test
    void 清空購物車回204() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);

        mockMvc.perform(delete("/api/cart")).andExpect(status().isNoContent());
        verify(cartService).clearCart(1);
    }
}
