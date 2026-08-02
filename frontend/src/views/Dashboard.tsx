import React, { useEffect, useState } from 'react';
import { HeroMetric } from '../components/HeroMetric';
import { AnomalyBanner } from '../components/AnomalyBanner';
import { SystemHealth } from '../components/SystemHealth';
import { Scorecard } from '../components/Scorecard';
import { TransactionFeed } from '../components/TransactionFeed';
import { getTransactions } from '../services/api';
import type { Transaction } from '../types';
import { useWebSocket } from '../services/WebSocketContext';

export const Dashboard: React.FC = () => {
  const [initialTransactions, setInitialTransactions] = useState<Transaction[]>([]);
  const { liveTransactions } = useWebSocket();

  useEffect(() => {
    const loadInitial = async () => {
      try {
        const data = await getTransactions(0);
        if (data && data.content) {
          setInitialTransactions(data.content);
        } else {
          setInitialTransactions([]);
        }
      } catch (error) {
        console.error("Failed to fetch initial transactions", error);
        setInitialTransactions([]);
      }
    };
    loadInitial();
  }, []);

  // Merge initial transactions with live updates
  const displayTransactions = [...initialTransactions];
  
  // Apply live updates
  Object.values(liveTransactions).forEach(liveTxn => {
    const idx = displayTransactions.findIndex(t => t.txnId === liveTxn.txnId);
    if (idx !== -1) {
      displayTransactions[idx] = {
        ...displayTransactions[idx],
        state: liveTxn.newState,
        penaltyAmountInr: liveTxn.penaltyAmountInr
      };
    }
  });

  return (
    <div className="container">
      <header style={{ marginBottom: '2rem' }}>
        <h1 style={{ fontSize: '1.75rem', marginBottom: '0.25rem' }}>UPI Reconciliation Console</h1>
        <p style={{ color: 'var(--text-muted)', margin: 0 }}>Regulatory Compliance & Audit Dashboard</p>
      </header>

      <AnomalyBanner />
      
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 350px', gap: '1.5rem', marginBottom: '1.5rem' }}>
        <div>
          <HeroMetric />
          <SystemHealth transactions={displayTransactions} />
          <TransactionFeed transactions={displayTransactions} />
        </div>
        <div>
          <Scorecard />
        </div>
      </div>
    </div>
  );
};
