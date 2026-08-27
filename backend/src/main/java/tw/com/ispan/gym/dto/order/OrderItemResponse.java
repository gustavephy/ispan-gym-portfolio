package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.entity.trainer.Trainer;

import java.math.BigDecimal;

/**
 * 訂單單一品項。{@code productId} 與 {@code privateCourseId} 恰好一個非空，對應 order_item 的雙 FK；
 * {@code itemType} 直接標明是商品還是私教課，前端不必自己從哪個 id 非空去推。
 * 私教課品項另外帶出堂數與教練資訊（{@code sessionPerUnit}／{@code totalSessions}／{@code trainerName}），
 * 商品品項這三個欄位為 {@code null}。
 */
public record OrderItemResponse(
        Integer orderItemId,
        Integer productId,
        Integer privateCourseId,
        String productNameAtBuy,
        BigDecimal priceAtBuy,
        Integer quantity,
        BigDecimal lineAmount,
        String itemType,
        Integer sessionPerUnit,
        Integer totalSessions,
        String trainerName) {

    /** 品項型別；與 order_item 雙 FK 哪一側非空一致 */
    public static final String TYPE_PRODUCT = "PRODUCT";
    public static final String TYPE_COURSE = "COURSE";

    /**
     * 由 order_item 實體組出回應。私教課欄位需在載入交易內取用（{@code course}／{@code trainer}
     * 皆為 LAZY 關聯），呼叫端 {@code OrderQueryService}／{@code AdminOrderQueryService}／
     * {@code OrderService} 都在 {@code @Transactional} 內，故安全。
     */
    public static OrderItemResponse from(OrderItem item) {
        BigDecimal lineAmount = item.getPriceAtBuy().multiply(BigDecimal.valueOf(item.getQuantity()));
        if (!item.isCourseItem()) {
            return new OrderItemResponse(item.getId(), item.getProduct().getProductId(), null,
                    item.getProductNameAtBuy(), item.getPriceAtBuy(), item.getQuantity(), lineAmount,
                    TYPE_PRODUCT, null, null, null);
        }
        PrivateCourse course = item.getCourse();
        Integer sessionPerUnit = course.getSessionPerUnit();
        Integer totalSessions = sessionPerUnit == null ? null : sessionPerUnit * item.getQuantity();
        return new OrderItemResponse(item.getId(), null, course.getId(),
                item.getProductNameAtBuy(), item.getPriceAtBuy(), item.getQuantity(), lineAmount,
                TYPE_COURSE, sessionPerUnit, totalSessions, trainerName(course.getTrainer()));
    }

    private static String trainerName(Trainer trainer) {
        return trainer == null ? null : trainer.getFirstName() + trainer.getLastName();
    }
}
