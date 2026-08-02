package com.upi.reconcile.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-SockJS WebSocket configuration — ARCHITECTURE.md §7.
 * <p>
 * Registers {@code /ws/live-feed} as a SockJS-enabled STOMP endpoint.
 * The simple in-memory broker relays messages on {@code /topic/*} destinations.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable a simple in-memory broker for /topic destinations
        registry.enableSimpleBroker("/topic");
        // Application-bound messages use /app prefix
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/live-feed")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
