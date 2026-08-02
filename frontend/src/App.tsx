import React, { useEffect, useState } from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { WebSocketProvider } from './services/WebSocketContext';
import { Dashboard } from './views/Dashboard';
import { AuditHistory } from './views/AuditHistory';
import { getTransactions } from './services/api';
import type { Transaction } from './types';

const App: React.FC = () => {
  const [initialTransactions, setInitialTransactions] = useState<Transaction[]>([]);
  const [loading, setLoading] = useState(true);

  // We fetch initial transactions here so WebSocketProvider can initialize the totalRecovered metric correctly.
  useEffect(() => {
    const fetchInitial = async () => {
      try {
        const data = await getTransactions(0);
        if (data && data.content) {
          setInitialTransactions(data.content);
        } else {
          setInitialTransactions([]);
        }
      } catch (error) {
        console.error("Failed to fetch initial transactions for App context", error);
        setInitialTransactions([]);
      } finally {
        setLoading(false);
      }
    };
    
    fetchInitial();
  }, []);

  if (loading) {
    return <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-muted)' }}>Initializing Application...</div>;
  }

  return (
    <WebSocketProvider initialTransactions={initialTransactions}>
      <Router>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/history/:txnId" element={<AuditHistory />} />
        </Routes>
      </Router>
    </WebSocketProvider>
  );
};

export default App;
