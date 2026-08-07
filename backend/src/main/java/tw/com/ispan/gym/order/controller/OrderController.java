package tw.com.ispan.gym.order.controller;

import tw.com.ispan.gym.order.dto.OrderDetailResponse;
import tw.com.ispan.gym.order.dto.OrderSummaryResponse;
import tw.com.ispan.gym.order.service.OrderQueryService;
import tw.com.ispan.gym.security.CurrentUserProvider;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderQueryService orderQueryService;
    private final CurrentUserProvider currentUser;

    public OrderController(OrderQueryService orderQueryService, CurrentUserProvider currentUser) {
        this.orderQueryService = orderQueryService; this.currentUser = currentUser;
    }

    @GetMapping
    public List<OrderSummaryResponse> findMine() {
        return orderQueryService.findMine(currentUser.requireCurrentUserId());
    }

    @GetMapping("/{orderId}")
    public OrderDetailResponse findMineById(@PathVariable Long orderId) {
        return orderQueryService.findMineById(currentUser.requireCurrentUserId(), orderId);
    }
}

