package tw.com.ispan.gym.repository.order;

import tw.com.ispan.gym.dto.report.ProductSalesRow;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.enums.order.OrderStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Integer> {
    @Query("SELECT oi FROM OrderItem oi WHERE oi.order.id = :orderId ORDER BY oi.orderItemId ASC")
    List<OrderItem> findAllByOrderIdOrderByIdAsc(@Param("orderId") Integer orderId);

    /**
     * 一次撈多筆訂單的明細（「我的訂單」列表用，避免逐筆查的 N+1）。
     * 呼叫端自行依 order id 分組；空集合直接回空 List。
     */
    List<OrderItem> findAllByOrderIdInOrderByOrderIdAscIdAsc(Collection<Integer> orderIds);

    /**
     * 這筆訂單是否含實體商品（product_id 非空的明細列）。
     * 用於「完成訂單」前判斷該不該要求先出貨——私教課與訂閱方案沒有實體要寄，
     * 付款後即可直接完成；實體商品則必須經過出貨。
     */
    boolean existsByOrderIdAndProductIsNotNull(Integer orderId);

    /**
     * 商品／私教課銷售聚合（報表用）。統計以 order_item 為準（成交當下的快照數字），
     * 名稱取 MAX(productNameAtBuy) 快照，改名後不影響歷史統計。
     * 雙 FK 用 LEFT JOIN 各自帶出 id，品項型別由 service 依哪一側非空判別
     *（product_id ⊕ private_course_id 恰一非空由 DB CHECK 保證）；
     * 訂閱單無明細列，由 OrderRepository.aggregateSubscriptionSales 另行聚合。
     */
    @Query("""
            SELECT new tw.com.ispan.gym.dto.report.ProductSalesRow(
                p.productId, c.id, MAX(oi.productNameAtBuy),
                SUM(oi.quantity), SUM(oi.priceAtBuy * oi.quantity))
            FROM OrderItem oi
            JOIN oi.order o
            LEFT JOIN oi.product p
            LEFT JOIN oi.course c
            WHERE o.paidAt >= :from AND o.paidAt < :to
              AND o.status IN :statuses
            GROUP BY p.productId, c.id
            ORDER BY SUM(oi.priceAtBuy * oi.quantity) DESC
            """)
    List<ProductSalesRow> aggregateItemSales(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("statuses") Collection<OrderStatus> statuses);
}
