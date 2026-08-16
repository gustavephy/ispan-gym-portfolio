package tw.com.ispan.gym.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import tw.com.ispan.gym.security.StompAuthChannelInterceptor;

/**
 * 聊天室（2.4）WebSocket 設定：STOMP over SockJS。
 * 端點 /ws-chat；推送頻道 /topic/room/{roomId}；接收端點 /app/chat.send、/app/chat.reply。
 *
 * <p>此設定只註冊記憶體內建的 simple broker（訊息本身一律先落地 DB 才推送，
 * 見 ChatService#saveAndPush），不需要 RabbitMQ／ActiveMQ 這類外部 broker。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authChannelInterceptor;

    /** 沿用 SecurityConfig 同一份允許來源設定，避免兩處各自維護造成漂移 */
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private List<String> allowedOrigins;

    public WebSocketConfig(StompAuthChannelInterceptor authChannelInterceptor) {
        this.authChannelInterceptor = authChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(@NonNull StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-chat")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new))
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry registry) {
        // /topic：房間廣播；/user：@SendToUser 的個人錯誤回報頻道
        registry.enableSimpleBroker("/topic", "/user");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(@NonNull ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
