package tw.com.ispan.gym.controller.order;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.order.AdminOrderDetailResponse;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.service.order.AdminOrderQueryService;
import tw.com.ispan.gym.service.order.OrderService;
import tw.com.ispan.gym.service.payment.EcpayService;
import tw.com.ispan.gym.service.report.OperationReportService;

/** AdminOrderController 的 controller 層測試（standalone MockMvc）——聚焦模擬付款端點。 */
class AdminOrderControllerTest {

    private final OperationReportService reportService = mock(OperationReportService.class);
    private final OrderService orderService = mock(OrderService.class);
    private final AdminOrderQueryService orderQueryService = mock(AdminOrderQueryService.class);
    private final EcpayService ecpayService = mock(EcpayService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AdminOrderController(reportService, orderService, orderQueryService, ecpayService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private AdminOrderDetailResponse detail() {
        return new AdminOrderDetailResponse(9, "ORD9", "GYM9", "TRADE9", "乳清蛋白", 1, "王小明", "a@b.c",
                new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"), OrderStatus.PAID, "Simulated", null,
                LocalDateTime.now(), null, null, "王小明", "0912345678", "地址", "a@b.c", LocalDateTime.now(),
                List.of(), null, List.of());
    }

    @Test
    void 模擬付款成功回傳更新後的訂單詳情() throws Exception {
        when(orderQueryService.findById(9)).thenReturn(detail());

        mockMvc.perform(post("/api/admin/orders/9/simulate-payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        verify(ecpayService).simulatePaymentSuccess(9);
    }

    @Test
    void 模擬付款對已付款訂單時由service丟出對應到409() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessRuleException("只有未付款的訂單可以模擬付款"))
                .when(ecpayService).simulatePaymentSuccess(9);

        mockMvc.perform(post("/api/admin/orders/9/simulate-payment"))
                .andExpect(status().isConflict());
    }
}
