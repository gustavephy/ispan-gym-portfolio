package tw.com.ispan.gym.controller.subscription;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tw.com.ispan.gym.dto.subscription.SubscriptionPlanResponse;
import tw.com.ispan.gym.dto.subscription.SubscriptionResponse;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.member.MemberSubscriptionRepository;
import tw.com.ispan.gym.repository.subscription.SubscriptionPlanRepository;
import tw.com.ispan.gym.security.CurrentUserProvider;

/**
 * 訂閱方案瀏覽與會員自身訂閱狀態查詢。實際的訂閱結帳走
 * {@code OrderController#checkoutDirectSubscription}，這裡只負責查詢面。
 */
@RestController
public class SubscriptionController {
    private final SubscriptionPlanRepository subscriptionPlans;
    private final MemberSubscriptionRepository subscriptions;
    private final CurrentUserProvider currentUser;

    public SubscriptionController(SubscriptionPlanRepository subscriptionPlans,
            MemberSubscriptionRepository subscriptions, CurrentUserProvider currentUser) {
        this.subscriptionPlans = subscriptionPlans;
        this.subscriptions = subscriptions;
        this.currentUser = currentUser;
    }

    /** 公開瀏覽目前可購買的訂閱方案，未登入者也可查看（SecurityConfig 已 permitAll） */
    @GetMapping("/api/subscription-plans")
    public List<SubscriptionPlanResponse> listPlans() {
        return subscriptionPlans.findByStatus("ACTIVE").stream()
                .map(SubscriptionPlanResponse::from)
                .toList();
    }

    /** 查詢自己名下最新一筆訂閱（依到期日排序取第一筆），供前端判斷目前是否仍在有效期內 */
    @GetMapping("/api/me/subscription")
    public SubscriptionResponse getMySubscription() {
        return subscriptions.findByMemberIdOrderByEndDateDesc(currentUser.requireCurrentMemberId())
                .stream()
                .findFirst()
                .map(SubscriptionResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("尚無任何訂閱紀錄"));
    }
}
