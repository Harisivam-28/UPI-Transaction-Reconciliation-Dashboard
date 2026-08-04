import { useEffect, useRef, useState, useCallback } from 'react';
import { Client } from '@stomp/stompjs';
import {
  createWebSocketClient,
  type ConnectionStatus,
} from '../api/websocket';
import type {
  WebSocketMessage,
  LiveFeedMessage,
  AnomalyMessage,
} from '../api/types';
import { isAnomalyMessage } from '../api/types';

export interface UseWebSocketReturn {
  status: ConnectionStatus;
  liveMessages: LiveFeedMessage[];
  anomalies: AnomalyMessage[];
  dismissAnomaly: (index: number) => void;
}

/**
 * Manages the STOMP-over-SockJS WebSocket lifecycle.
 * Buffers the most recent live-feed messages and anomaly alerts.
 */
export function useWebSocket(maxMessages = 100): UseWebSocketReturn {
  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const [liveMessages, setLiveMessages] = useState<LiveFeedMessage[]>([]);
  const [anomalies, setAnomalies] = useState<AnomalyMessage[]>([]);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    const client = createWebSocketClient({
      onStatusChange: setStatus,
      onMessage: (msg: WebSocketMessage) => {
        if (isAnomalyMessage(msg)) {
          setAnomalies((prev) => [msg, ...prev]);
        } else {
          setLiveMessages((prev) => {
            const next = [msg, ...prev];
            return next.slice(0, maxMessages);
          });
        }
      },
    });
    clientRef.current = client;

    return () => {
      client.deactivate();
    };
  }, [maxMessages]);

  const dismissAnomaly = useCallback((index: number) => {
    setAnomalies((prev) => prev.filter((_, i) => i !== index));
  }, []);

  return { status, liveMessages, anomalies, dismissAnomaly };
}
