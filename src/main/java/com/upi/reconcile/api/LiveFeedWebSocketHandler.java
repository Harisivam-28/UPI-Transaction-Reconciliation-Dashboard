package com.upi.reconcile.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for live transaction feed — ARCHITECTURE.md §7.
 * <p>
 * WS /ws/live-feed
 *   → pushes { txn_id, old_state, new_state, penalty_amount_inr, bank_id } on every transition
 * <p>
 * TODO: Integrate with state machine to broadcast real transitions.
 */
@Slf4j
@Component
public class LiveFeedWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket client connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket client disconnected: {}", session.getId());
    }

    /**
     * Broadcast a message to all connected clients.
     */
    public void broadcast(String message) {
        sessions.forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            } catch (IOException e) {
                log.error("Failed to send WebSocket message to {}", session.getId(), e);
            }
        });
    }
}
