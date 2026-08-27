package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;

import java.math.BigDecimal;

/**
 * 訂閱訂單購買的方案快照。訂閱不走 order_item 多型（方案記在 {@code order.subscriptionPlan}，
 * 見 {@link tw.com.ispan.gym.entity.order.Order}），所以訂單明細另外用這個結構帶出，
 * 讓「我的訂單」能完整顯示買了哪個方案、期間多久。非訂閱訂單為 {@code null}。
 */
public record OrderSubscriptionResponse(
        Integer subscriptionPlanId,
        String name,
        String description,
        BigDecimal price,
        Integer durationDays) {

    public static OrderSubscriptionResponse from(SubscriptionPlan plan) {
        if (plan == null) {
            return null;
        }
        return new OrderSubscriptionResponse(plan.getSubscriptionPlanId(), plan.getName(),
                plan.getDescription(), plan.getPrice(), plan.getDurationDays());
    }
}
