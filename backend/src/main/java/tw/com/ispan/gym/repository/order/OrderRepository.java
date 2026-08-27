package tw.com.ispan.gym.repository.order;

import tw.com.ispan.gym.dto.report.DailyRevenueRow;
import tw.com.ispan.gym.dto.report.OrderPeriodSummary;
import tw.com.ispan.gym.dto.report.SubscriptionSalesRow;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.enums.order.OrderStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Integer> {
        List<Order> findAllByMemberIdOrderByCreatedAtDesc(Integer memberId);

        @Query("SELECT o FROM Order o WHERE o.orderId = :orderId AND o.member.id = :memberId")
        Optional<Order> findByIdAndMemberId(@Param("orderId") Integer orderId, @Param("memberId") Integer memberId);

        Optional<Order> findByMerchantTradeNo(String merchantTradeNo);

        /**
         * 依重試後綴前的原始編號找訂單。
         *
         * <p>每次重新結帳都會換一組新的 MerchantTradeNo（見 Order#renewMerchantTradeNo），
         * 綠界若對「使用者先前放棄的那一次」送來遲到通知，帶的會是舊編號，
         * 直接用 findByMerchantTradeNo 會查不到。改以共同前綴回推同一筆訂單。
         */
        Optional<Order> findByMerchantTradeNoStartingWith(String prefix);

        Optional<Order> findByTradeNo(String tradeNo);

        boolean existsByMerchantTradeNo(String merchantTradeNo);

        boolean existsByOrderNo(String orderNo);

        /** 逾時未付款訂單清理用：撈出指定狀態、且早於 cutoff 建立的訂單 */
        List<Order> findAllByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime cutoff);

        /**
         * 報表區間總計。時間軸用 paidAt（營收歸屬付款日，非下單日）；
         * 空區間的兩個 SUM 會是 null，由 service 端歸零。
         */
        @Query("""
                        SELECT new tw.com.ispan.gym.dto.report.OrderPeriodSummary(
                            COUNT(o), SUM(o.totalAmount), SUM(o.discountAmount))
                        FROM Order o
                        WHERE o.paidAt >= :from AND o.paidAt < :to
                          AND o.status IN :statuses
                        """)
        OrderPeriodSummary summarizePeriod(
                        @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to,
                        @Param("statuses") Collection<OrderStatus> statuses);

        /**
         * 訂閱銷售聚合（報表用）：訂閱單刻意不進 order_item（設計定案），
         * 直接聚合帶 subscription_plan_id 的訂單。金額用 subtotalAmount
         * （折價前），與明細排行的 priceAtBuy × quantity 口徑一致。
         */
        @Query("""
                        SELECT new tw.com.ispan.gym.dto.report.SubscriptionSalesRow(
                            sp.subscriptionPlanId, sp.name, COUNT(o), SUM(o.subtotalAmount))
                        FROM Order o
                        JOIN o.subscriptionPlan sp
                        WHERE o.paidAt >= :from AND o.paidAt < :to
                          AND o.status IN :statuses
                        GROUP BY sp.subscriptionPlanId, sp.name
                        """)
        List<SubscriptionSalesRow> aggregateSubscriptionSales(
                        @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to,
                        @Param("statuses") Collection<OrderStatus> statuses);

        /**
         * 每日營收聚合（報表趨勢用）。口徑同 summarizePeriod：paidAt 時間軸、
         * 僅計入指定狀態；CAST 至 LocalDate 由方言轉為資料庫層的日期截斷。
         */
        @Query("""
                        SELECT new tw.com.ispan.gym.dto.report.DailyRevenueRow(
                            CAST(o.paidAt AS LocalDate), COUNT(o), SUM(o.totalAmount))
                        FROM Order o
                        WHERE o.paidAt >= :from AND o.paidAt < :to
                          AND o.status IN :statuses
                        GROUP BY CAST(o.paidAt AS LocalDate)
                        ORDER BY CAST(o.paidAt AS LocalDate)
                        """)
        List<DailyRevenueRow> aggregateDailyRevenue(
                        @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to,
                        @Param("statuses") Collection<OrderStatus> statuses);

        @Query("SELECT o.status, COUNT(o), SUM(o.totalAmount) FROM Order o "
                        + "WHERE o.createdAt BETWEEN :from AND :to GROUP BY o.status")
        List<Object[]> aggregateByStatus(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

        @Query("SELECT o.paymentMethod, COUNT(o) FROM Order o "
                        + "WHERE o.createdAt BETWEEN :from AND :to AND o.paymentMethod IS NOT NULL GROUP BY o.paymentMethod")
        List<Object[]> aggregateByPaymentMethod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

        /**
         * 後台訂單搜尋，各條件皆為選填（傳 null 代表不篩選這項）。
         * keyword 需由呼叫端先轉小寫並包上 {@code %}（LIKE 萬用字元），比對訂單編號/綠界特店編號/
         * 收件人姓名/會員信箱四個欄位；日期區間比對下單時間 createdAt（含頭不含尾，呼叫端自行組半開區間）。
         */
        @Query("""
                        SELECT o FROM Order o JOIN o.member m JOIN m.account a
                        WHERE (:status IS NULL OR o.status = :status)
                          AND (:memberId IS NULL OR m.id = :memberId)
                          AND (:from IS NULL OR o.createdAt >= :from)
                          AND (:to IS NULL OR o.createdAt < :to)
                          AND (:keyword IS NULL
                               OR LOWER(o.orderNo) LIKE :keyword
                               OR LOWER(o.merchantTradeNo) LIKE :keyword
                               OR LOWER(o.receiverName) LIKE :keyword
                               OR LOWER(a.email) LIKE :keyword)
                        """)
        Page<Order> search(@Param("status") OrderStatus status, @Param("memberId") Integer memberId,
                        @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                        @Param("keyword") String keyword, Pageable pageable);
}
