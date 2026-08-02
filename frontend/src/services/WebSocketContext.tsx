import React, { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { LiveFeedMessage, AnomalyMessage, Transaction } from '../types';

interface WebSocketContextType {
  connected: boolean;
  liveTransactions: Record<string, LiveFeedMessage>;
  anomalies: AnomalyMessage[];
  totalRecovered: number;
}

const WebSocketContext = createContext<WebSocketContextType>({
  connected: false,
  liveTransactions: {},
  anomalies: [],
  totalRecovered: 0,
});

export const useWebSocket = () => useContext(WebSocketContext);

interface ProviderProps {
  children: ReactNode;
  initialTransactions?: Transaction[]; // Pass initial loaded to sync state
}

export const WebSocketProvider: React.FC<ProviderProps> = ({ children, initialTransactions = [] }) => {
  const [connected, setConnected] = useState(false);
  const [liveTransactions, setLiveTransactions] = useState<Record<string, LiveFeedMessage>>({});
  const [anomalies, setAnomalies] = useState<AnomalyMessage[]>([]);
  const [totalRecovered, setTotalRecovered] = useState(0);

  // Initialize total recovered from initial transactions
  useEffect(() => {
    let initialRecovered = 0;
    initialTransactions.forEach(t => {
      if (t.state === 'RESOLVED_REFUNDED') {
        initialRecovered += t.penaltyAmountInr || 0;
      }
    });
    setTotalRecovered(initialRecovered);
  }, [initialTransactions]);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws/live-feed'),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe('/topic/live-feed', (message) => {
          const body = JSON.parse(message.body);
          
          if (body.eventType === 'ANOMALY_FLAGGED') {
            setAnomalies(prev => [body as AnomalyMessage, ...prev].slice(0, 5));
          } else {
            const feedMsg = body as LiveFeedMessage;
            setLiveTransactions(prev => ({
              ...prev,
              [feedMsg.txnId]: feedMsg
            }));

            if (feedMsg.newState === 'RESOLVED_REFUNDED') {
              setTotalRecovered(prev => prev + (feedMsg.penaltyAmountInr || 0));
            }
          }
        });
      },
      onDisconnect: () => setConnected(false),
      onStompError: (frame) => {
        console.error('Broker reported error: ' + frame.headers['message']);
        console.error('Additional details: ' + frame.body);
      },
    });

    client.activate();

    return () => {
      client.deactivate();
    };
  }, []);

  return (
    <WebSocketContext.Provider value={{ connected, liveTransactions, anomalies, totalRecovered }}>
      {children}
    </WebSocketContext.Provider>
  );
};
