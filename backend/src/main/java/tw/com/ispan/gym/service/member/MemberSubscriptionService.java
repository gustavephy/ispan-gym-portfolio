package tw.com.ispan.gym.service.member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tw.com.ispan.gym.dto.order.OrderResponse;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.subscription.SubscriptionPlanRepository;
import tw.com.ispan.gym.service.common.EcpayService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberSubscriptionService {

        @Value("${ecpay.callback.base-url}")
        private String ecpayCallbackBaseUrl;

        @Value("${app.frontend-url:http://localhost:5174}")
        private String frontendUrl;

        private final SubscriptionPlanRepository subscriptionPlanRepository;
        private final MemberRepository memberRepository;
        private final OrderRepository orderRepository;
        private final EcpayService ecpayService;

        @Transactional
        public OrderResponse checkoutSubscription(Integer memberId, Integer planId, String paymentMethod,
                        String dynamicBackendUrl, String dynamicFrontendUrl) {
                Member member = memberRepository.findById(memberId)
                                .orElseThrow(() -> new ResourceNotFoundException("會員不存在"));

                SubscriptionPlan plan = subscriptionPlanRepository.findById(planId)
                                .orElseThrow(() -> new ResourceNotFoundException("訂閱方案不存在"));

                if (!"ACTIVE".equalsIgnoreCase(plan.getStatus())) {
                        throw new BusinessRuleException("此訂閱方案目前未啟用");
                }

                // 建立訂閱的訂單
                Order order = new Order();
                order.setMember(member);
                order.setTotalAmount(plan.getPrice());
                order.setStatus(OrderStatus.UNPAID);
                order.setPaymentMethod(paymentMethod != null ? paymentMethod.toLowerCase() : "ecpay");
                order.setSubscriptionPlan(plan);
                order.setDeliveryMethod("ONLINE");
                order.setInvoiceType("personal");
                order.setCarrierType("member");

                // 綠界交易編號
                order.setMerchantTradeNo(
                                "SUB" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                                                + memberId);
                Order savedOrder = orderRepository.save(order);

                log.info("Subscription order created successfully: #{}, plan: {}, price: {}", savedOrder.getOrderId(),
                                plan.getName(), plan.getPrice());

                OrderResponse response = OrderResponse.from(savedOrder);

                // 生成綠界支付表單
                if ("ecpay".equalsIgnoreCase(savedOrder.getPaymentMethod())) {
                        String callbackUrl = (dynamicBackendUrl != null && !dynamicBackendUrl.isEmpty()
                                        && !dynamicBackendUrl.contains("localhost")
                                        && !dynamicBackendUrl.contains("127.0.0.1")
                                        && !dynamicBackendUrl.contains("backend"))
                                                        ? dynamicBackendUrl
                                                        : ecpayCallbackBaseUrl;
                        String paymentHtml = ecpayService.generatePaymentForm(
                                        savedOrder.getMerchantTradeNo(),
                                        savedOrder.getTotalAmount().intValue(),
                                        "健身房會員訂閱: " + plan.getName(),
                                        plan.getName(),
                                        callbackUrl + "/api/orders/ecpay-webhook",
                                        (dynamicFrontendUrl != null && !dynamicFrontendUrl.isEmpty())
                                                        ? dynamicFrontendUrl
                                                        : (frontendUrl + "/orders"),
                                        dynamicBackendUrl,
                                        dynamicFrontendUrl);
                        response.setPaymentHtml(paymentHtml);
                }

                return response;
        }
}
