import { useState, useEffect, useCallback } from 'react';
import { useWebSocket } from './hooks/useWebSocket';
import { HeroCounter } from './components/HeroCounter';
import { AnomalyBanner } from './components/AnomalyBanner';
import { LiveFeed } from './components/LiveFeed';
import { BankScorecard } from './components/BankScorecard';
import { TransactionDetail } from './components/TransactionDetail';
import { GatewayOnboarding } from './components/GatewayOnboarding';
import { getTransactions } from './api/transactions';
import type { TransactionDto, PageResponse } from './api/types';

type View = 'onboarding' | 'dashboard';

function App() {
  const { status, liveMessages, anomalies, dismissAnomaly } = useWebSocket();
  const [selectedTxnId, setSelectedTxnId] = useState<string | null>(null);
  const [heroData, setHeroData] = useState({
    totalRecovered: 0,
    totalPenaltyTxns: 0,
    totalTxns: 0,
  });

  // Determine initial view based on whether a gateway has been connected
  const [view, setView] = useState<View>(() => {
    return localStorage.getItem('gateway_connected') === 'true' ? 'dashboard' : 'onboarding';
  });

  const handleGatewayConnected = () => {
    localStorage.setItem('gateway_connected', 'true');
    setView('dashboard');
  };

  // Compute hero data from all RESOLVED_REFUNDED transactions
  const fetchHeroData = useCallback(async () => {
    try {
      // Get total count of all transactions
      const allTxns: PageResponse<TransactionDto> = await getTransactions({ page: 0 });
      
      // Get resolved/refunded transactions to sum penalties
      const resolvedTxns: PageResponse<TransactionDto> = await getTransactions({
        state: 'RESOLVED_REFUNDED',
        page: 0,
      });

      let totalRecovered = 0;
      let totalPenaltyTxns = resolvedTxns.totalElements;

      // Sum up penalties from the first page
      for (const txn of resolvedTxns.content) {
        totalRecovered += txn.penaltyAmountInr ?? 0;
      }

      // If there are more pages, fetch them too
      for (let p = 1; p < resolvedTxns.totalPages; p++) {
        const page = await getTransactions({ state: 'RESOLVED_REFUNDED', page: p });
        for (const txn of page.content) {
          totalRecovered += txn.penaltyAmountInr ?? 0;
        }
      }

      setHeroData({
        totalRecovered,
        totalPenaltyTxns,
        totalTxns: allTxns.totalElements,
      });
    } catch (err) {
      console.error('Failed to compute hero data:', err);
    }
  }, []);

  useEffect(() => {
    if (view === 'dashboard') {
      fetchHeroData();
    }
  }, [fetchHeroData, view]);

  // Refresh hero when new WS messages arrive (debounced)
  useEffect(() => {
    if (liveMessages.length === 0 || view !== 'dashboard') return;
    const timer = setTimeout(() => fetchHeroData(), 1500);
    return () => clearTimeout(timer);
  }, [liveMessages.length, fetchHeroData, view]);

  return (
    <div className="app-container">
      {/* ── Header ─────────────────────────────────────── */}
      <header className="app-header">
        <div className="app-header__logo">
          <div className="app-header__icon">₹</div>
          <div>
            <div className="app-header__title">UPI Recovery Dashboard</div>
            <div className="app-header__subtitle">Automated refund tracking & complaint filing</div>
          </div>
        </div>
        <div className="app-header__nav">
          {localStorage.getItem('gateway_connected') === 'true' && (
            <>
              <button
                className={`nav-link ${view === 'dashboard' ? 'nav-link--active' : ''}`}
                onClick={() => setView('dashboard')}
              >
                Dashboard
              </button>
              <button
                className={`nav-link ${view === 'onboarding' ? 'nav-link--active' : ''}`}
                onClick={() => setView('onboarding')}
              >
                Connections
              </button>
            </>
          )}
          <div className="app-header__status">
            <span className={`status-dot ${status !== 'connected' ? 'status-dot--disconnected' : ''}`} />
            {status === 'connected'
              ? 'Live'
              : status === 'connecting'
              ? 'Connecting…'
              : 'Disconnected'}
          </div>
        </div>
      </header>

      {/* ── Onboarding View ─────────────────────────────── */}
      {view === 'onboarding' && (
        <GatewayOnboarding onConnected={handleGatewayConnected} />
      )}

      {/* ── Dashboard View (existing — untouched) ─────── */}
      {view === 'dashboard' && (
        <>
          {/* ── Anomaly Banners ─────────────────────────────── */}
          <AnomalyBanner anomalies={anomalies} onDismiss={dismissAnomaly} />

          {/* ── Hero Counter ────────────────────────────────── */}
          <HeroCounter
            totalRecovered={heroData.totalRecovered}
            totalPenaltyTransactions={heroData.totalPenaltyTxns}
            totalTransactions={heroData.totalTxns}
          />

          {/* ── Main Grid: Feed + Scorecard ─────────────────── */}
          <div className="main-grid">
            <LiveFeed
              liveMessages={liveMessages}
              onSelectTransaction={setSelectedTxnId}
            />
            <BankScorecard liveMessages={liveMessages} />
          </div>

          {/* ── Transaction Detail Drawer ───────────────────── */}
          {selectedTxnId && (
            <TransactionDetail
              txnId={selectedTxnId}
              onClose={() => setSelectedTxnId(null)}
            />
          )}
        </>
      )}
    </div>
  );
}

export default App;

