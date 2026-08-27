package tw.com.ispan.gym.controller.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.payment.EcpayService;

/**
 * PaymentController 的 controller 層測試：standalone MockMvc、依賴全 mock。
 * 重點在「通知端點無論如何都回 1|OK」與「結帳表單只給本人訂單」。
 */
class PaymentControllerTest {

    private final EcpayService ecpayService = mock(EcpayService.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PaymentController(ecpayService, orders, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 結帳表單_本人訂單回200與表單欄位() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        when(orders.findByIdAndMemberId(5, 1)).thenReturn(Optional.of(mock(Order.class)));
        when(ecpayService.buildCheckoutForm(any())).thenReturn(Map.of("MerchantID", "2000132", "CheckMacValue", "ABC"));

        mockMvc.perform(post("/api/payments/ecpay/checkout/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.MerchantID").value("2000132"));
    }

    @Test
    void 結帳表單_非本人訂單回404() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(1);
        when(orders.findByIdAndMemberId(5, 1)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/payments/ecpay/checkout/5"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 綠界通知_處理成功回1OK() throws Exception {
        mockMvc.perform(post("/api/payments/ecpay/notify").param("MerchantTradeNo", "GYM1").param("RtnCode", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("1|OK"));

        verify(ecpayService).handleNotify(any());
    }

    @Test
    void 綠界通知_handleNotify丟例外仍回1OK() throws Exception {
        doThrow(new RuntimeException("boom")).when(ecpayService).handleNotify(any());

        mockMvc.perform(post("/api/payments/ecpay/notify").param("MerchantTradeNo", "GYM1").param("RtnCode", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("1|OK"));
    }
}
