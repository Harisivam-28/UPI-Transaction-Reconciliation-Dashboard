import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { WebSocketMessage } from './types';

export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected';

export interface WebSocketCallbacks {
  onMessage: (msg: WebSocketMessage) => void;
  onStatusChange: (status: ConnectionStatus) => void;
}

/**
 * Creates a STOMP-over-SockJS client matching the backend's exact config:
 *   - Endpoint: /ws/live-feed  (SockJS-enabled)
 *   - Subscription: /topic/live-feed
 *   - Broker prefix: /topic
 *
 * @see WebSocketConfig.java — registerStompEndpoints + configureMessageBroker
 */
export function createWebSocketClient(callbacks: WebSocketCallbacks): Client {
  const client = new Client({
    // Use SockJS as the transport (matching backend's .withSockJS())
    webSocketFactory: () => new SockJS('/ws/live-feed') as unknown as WebSocket,

    // Reconnect on disconnect
    reconnectDelay: 3000,

    // Heartbeat settings
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,

    // Silence debug in production
    debug: (msg) => {
      if (import.meta.env.DEV) {
        console.log('[STOMP]', msg);
      }
    },

    onConnect: () => {
      callbacks.onStatusChange('connected');
      console.log('[WS] STOMP connected to /ws/live-feed');

      // Subscribe to the live feed topic
      client.subscribe('/topic/live-feed', (message) => {
        try {
          const payload: WebSocketMessage = JSON.parse(message.body);
          callbacks.onMessage(payload);
        } catch (err) {
          console.error('[WS] Failed to parse message:', err);
        }
      });
    },

    onDisconnect: () => {
      callbacks.onStatusChange('disconnected');
      console.log('[WS] STOMP disconnected');
    },

    onStompError: (frame) => {
      callbacks.onStatusChange('disconnected');
      console.error('[WS] STOMP error:', frame.headers['message']);
    },

    onWebSocketClose: () => {
      callbacks.onStatusChange('disconnected');
    },
  });

  callbacks.onStatusChange('connecting');
  client.activate();

  return client;
}
