package tw.com.ispan.gym.controller.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.security.InMemoryRateLimiter;
import tw.com.ispan.gym.service.idempotency.IdempotencyService;
import tw.com.ispan.gym.service.order.OrderQueryService;
import tw.com.ispan.gym.service.order.OrderService;
import tw.com.ispan.gym.service.payment.EcpayService;

/**
 * OrderController 的 controller 層測試：standalone MockMvc、依賴全 mock。
 * 驗證請求驗證、狀態碼、Idempotency-Key 串接與建單節流；授權由 EndpointAuthorizationTest 覆蓋。
 */
class OrderControllerTest {

    private final OrderQueryService orderQueryService = mock(OrderQueryService.class);
    private final OrderService orderService = mock(OrderService.class);
    private final EcpayService ecpayService = mock(EcpayService.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final InMemoryRateLimiter rateLimiter = mock(InMemoryRateLimiter.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new OrderController(orderQueryService, orderService, ecpayService, orders, currentUser,
                    idempotency, rateLimiter, 10, Duration.ofMinutes(1)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private OrderDetailResponse detail(int id) {
        return new OrderDetailResponse(id, "GYM1", "乳清蛋白", new BigDecimal("1000"), OrderStatus.UNPAID,
                LocalDateTime.now(), List.of(), null, null);
    }

    private void loggedInAndNotRateLimited() {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        when(rateLimiter.tryAcquire(any(), eq(10), any())).thenReturn(true);
    }

    @Test
    void 購物車結帳成功回201並依冪等回傳的id查明細() throws Exception {
        loggedInAndNotRateLimited();
        when(idempotency.resolve(eq(1), eq("CHECKOUT_CART"), any(), any(), any())).thenReturn(55);
        when(orderQueryService.findMineById(1, 55)).thenReturn(detail(55));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-abc")
                        .content("{\"receiverAddress\":\"台北市中正區測試路1號\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(55));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(idempotency).resolve(eq(1), eq("CHECKOUT_CART"), key.capture(), any(), any());
        org.assertj.core.api.Assertions.assertThat(key.getValue()).isEqualTo("key-abc");
    }

    @Test
    void 建單超過節流上限時回429() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        when(rateLimiter.tryAcquire(any(), eq(10), any())).thenReturn(false);

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ORDER_RATE_LIMITED"));
    }

    @Test
    void 私教課結帳缺privateCourseId時回400() throws Exception {
        loggedInAndNotRateLimited();

        mockMvc.perform(post("/api/orders/direct").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.privateCourseId").exists());
    }

    @Test
    void 私教課結帳數量非正整數時回400() throws Exception {
        loggedInAndNotRateLimited();

        mockMvc.perform(post("/api/orders/direct").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"privateCourseId\":7,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").exists());
    }

    @Test
    void 取消訂單原因超過500字時回400() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        String longReason = "退".repeat(501);

        mockMvc.perform(post("/api/orders/1/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + longReason + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
    }

    @Test
    void 取消訂單成功回200() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        when(idempotency.resolve(eq(1), eq("CANCEL_ORDER:9"), any(), any(), any())).thenReturn(9);
        when(orderQueryService.findMineById(1, 9)).thenReturn(detail(9));

        mockMvc.perform(post("/api/orders/9/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"買錯了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(9));
    }

    @Test
    void 我的訂單列表回200() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        when(orderQueryService.findMine(1)).thenReturn(List.of());

        mockMvc.perform(get("/api/orders")).andExpect(status().isOk());
    }
}
