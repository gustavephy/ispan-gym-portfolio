package tw.com.ispan.gym.controller.subscription;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import tw.com.ispan.gym.dto.order.OrderResponse;
import tw.com.ispan.gym.security.AuthUtils;
import tw.com.ispan.gym.service.member.MemberSubscriptionService;

import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionController {

    private final MemberSubscriptionService subscriptionService;
    private final AuthUtils authUtils;

    @org.springframework.beans.factory.annotation.Value("${app.frontend-url:http://localhost:5174}")
    private String frontendUrl;

    // 取得後端基礎 URL（相容 Proxy/Docker/Local 等不同環境的連線 Host）
    private String getBackendBaseUrl(jakarta.servlet.http.HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isEmpty() && !referer.contains("localhost") && !referer.contains("127.0.0.1")) {
            try {
                java.net.URI uri = new java.net.URI(referer);
                return uri.getScheme() + "://" + uri.getAuthority() + request.getContextPath();
            } catch (Exception e) {
                // ignore and fallback
            }
        }

        String xForwardedHost = request.getHeader("X-Forwarded-Host");
        String xForwardedProto = request.getHeader("X-Forwarded-Proto");

        if (xForwardedHost != null && !xForwardedHost.isEmpty()) {
            if (xForwardedProto == null || xForwardedProto.isEmpty()) {
                if (xForwardedHost.contains("localhost") || xForwardedHost.contains("127.0.0.1")
                        || xForwardedHost.contains("backend")) {
                    xForwardedProto = "http";
                } else {
                    xForwardedProto = "https";
                }
            }
            if (xForwardedHost.startsWith("backend:")) {
                xForwardedHost = xForwardedHost.replace("backend:8080", "localhost:9080");
            } else if ("backend".equals(xForwardedHost)) {
                xForwardedHost = "localhost:9080";
            }
            return xForwardedProto + "://" + xForwardedHost + request.getContextPath();
        }

        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        String contextPath = request.getContextPath();

        if ("backend".equalsIgnoreCase(serverName)) {
            serverName = "localhost";
            serverPort = 9080;
            scheme = "http";
        }

        return scheme + "://" + serverName + (serverPort == 80 || serverPort == 443 ? "" : ":" + serverPort)
                + contextPath;
    }

    // 從 Referer 標頭或設定檔動態解析前端訂單頁面 URL
    private String getFrontendOrdersUrl(jakarta.servlet.http.HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isEmpty()) {
            try {
                java.net.URI uri = new java.net.URI(referer);
                return uri.getScheme() + "://" + uri.getAuthority() + "/orders";
            } catch (Exception e) {
            }
        }
        return frontendUrl + "/orders";
    }

    /**
     * 處理訂閱方案結帳，並回傳訂單與金流資訊
     */
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkoutSubscription(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam Integer planId,
            @RequestParam(required = false, defaultValue = "ecpay") String paymentMethod,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        Integer memberId = authUtils.getMemberId(userDetails);
        log.info("Subscription checkout requested for memberId: {}, planId: {}, paymentMethod: {}", memberId, planId,
                paymentMethod);

        String dynamicBackendUrl = getBackendBaseUrl(httpRequest);
        String dynamicFrontendUrl = getFrontendOrdersUrl(httpRequest);

        OrderResponse response = subscriptionService.checkoutSubscription(memberId, planId, paymentMethod,
                dynamicBackendUrl, dynamicFrontendUrl);
        return ResponseEntity.ok(response);
    }
}
