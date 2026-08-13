package tw.com.ispan.gym.service.report;

import tw.com.ispan.gym.dto.report.OrderOverviewResponse;
import tw.com.ispan.gym.enums.OrderStatus;
import tw.com.ispan.gym.repository.OrderRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 後台訂單總覽統計：依日期區間統計筆數/金額、依狀態與付款方式分布。 */
@Service
@Transactional(readOnly = true)
public class OperationReportService {
    private static final java.util.Set<OrderStatus> REVENUE_STATUSES =
            java.util.Set.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED);

    private final OrderRepository orders;

    public OperationReportService(OrderRepository orders) {
        this.orders = orders;
    }

    public OrderOverviewResponse getOrderOverview(LocalDateTime from, LocalDateTime to) {
        Map<String, Long> countByStatus = new LinkedHashMap<>();
        Map<String, BigDecimal> revenueByStatus = new LinkedHashMap<>();
        long totalOrders = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;

        for (Object[] row : orders.aggregateByStatus(from, to)) {
            OrderStatus status = (OrderStatus) row[0];
            long count = (Long) row[1];
            BigDecimal sum = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];

            countByStatus.put(status.name(), count);
            revenueByStatus.put(status.name(), sum);
            totalOrders += count;
            if (REVENUE_STATUSES.contains(status)) {
                totalRevenue = totalRevenue.add(sum);
            }
        }

        Map<String, Long> countByPaymentMethod = new LinkedHashMap<>();
        for (Object[] row : orders.aggregateByPaymentMethod(from, to)) {
            countByPaymentMethod.put((String) row[0], (Long) row[1]);
        }

        return new OrderOverviewResponse(from, to, totalOrders, totalRevenue, countByStatus, revenueByStatus,
                countByPaymentMethod);
    }
}
