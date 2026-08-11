package com.upi.reconcile.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import com.upi.reconcile.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Collections;
import java.util.UUID;

/**
 * STOMP-over-SockJS WebSocket configuration — ARCHITECTURE.md §7.
 * <p>
 * Registers {@code /ws/live-feed} as a SockJS-enabled STOMP endpoint.
 * The simple in-memory broker relays messages on {@code /topic/*} destinations.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtil jwtUtil;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable a simple in-memory broker for /topic and /user destinations
        registry.enableSimpleBroker("/topic", "/user");
        // Application-bound messages use /app prefix
        registry.setApplicationDestinationPrefixes("/app");
        // Set user destination prefix
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/live-feed")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.interceptors(new org.springframework.messaging.support.ChannelInterceptor() {
            @Override
            public org.springframework.messaging.Message<?> preSend(org.springframework.messaging.Message<?> message, org.springframework.messaging.MessageChannel channel) {
                org.springframework.messaging.simp.stomp.StompHeaderAccessor accessor =
                        org.springframework.messaging.simp.stomp.StompHeaderAccessor.wrap(message);
                
                if (org.springframework.messaging.simp.stomp.StompCommand.CONNECT.equals(accessor.getCommand())) {
                    java.util.List<String> authorization = accessor.getNativeHeader("Authorization");
                    if (authorization != null && !authorization.isEmpty()) {
                        String authHeader = authorization.get(0);
                        if (authHeader.startsWith("Bearer ")) {
                            String token = authHeader.substring(7);
                            if (jwtUtil.validateToken(token)) {
                                UUID merchantId = jwtUtil.extractMerchantId(token);
                                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                        merchantId, null, Collections.emptyList()
                                );
                                accessor.setUser(auth);
                            }
                        }
                    }
                }
                return message;
            }
        });
    }
}
