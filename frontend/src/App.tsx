import { useState, useEffect, useCallback } from 'react';
import { Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import { useWebSocket } from './hooks/useWebSocket';
import { HeroCounter } from './components/HeroCounter';
import { AnomalyBanner } from './components/AnomalyBanner';
import { LiveFeed } from './components/LiveFeed';
import { BankScorecard } from './components/BankScorecard';
import { TransactionDetail } from './components/TransactionDetail';
import { GatewayOnboarding } from './components/GatewayOnboarding';
import { Login } from './components/auth/Login';
import { SignUp } from './components/auth/SignUp';
import { getTransactions } from './api/transactions';
import type { TransactionDto, PageResponse } from './api/types';

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { token, isLoading } = useAuth();
  if (isLoading) {
    return <div style={{ color: 'var(--text-muted)', padding: '2rem' }}>Loading...</div>;
  }
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

// Layout for authenticated routes
function AuthenticatedLayout() {
  const { status, liveMessages, anomalies, dismissAnomaly } = useWebSocket();
  const { logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  const isDashboard = location.pathname === '/';
  
  return (
    <>
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
                className={`nav-link ${isDashboard ? 'nav-link--active' : ''}`}
                onClick={() => navigate('/')}
              >
                Dashboard
              </button>
              <button
                className={`nav-link ${location.pathname === '/onboarding' ? 'nav-link--active' : ''}`}
                onClick={() => navigate('/onboarding')}
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
          <button
            onClick={logout}
            className="nav-link"
            style={{ color: 'var(--danger)', marginLeft: '1rem', border: 'none', background: 'none', cursor: 'pointer' }}
          >
            Log out
          </button>
        </div>
      </header>
      
      <Routes>
        <Route path="/" element={
          <Dashboard
            liveMessages={liveMessages}
            anomalies={anomalies}
            dismissAnomaly={dismissAnomaly}
          />
        } />
        <Route path="/onboarding" element={
          <GatewayOnboarding onConnected={() => {
            localStorage.setItem('gateway_connected', 'true');
            navigate('/');
          }} />
        } />
      </Routes>
    </>
  );
}

// Separate component to keep Dashboard logic clean
function Dashboard({ liveMessages, anomalies, dismissAnomaly }: any) {
  const [selectedTxnId, setSelectedTxnId] = useState<string | null>(null);
  const [heroData, setHeroData] = useState({
    totalRecovered: 0,
    totalPenaltyTxns: 0,
    totalTxns: 0,
  });

  const fetchHeroData = useCallback(async () => {
    try {
      const allTxns: PageResponse<TransactionDto> = await getTransactions({ page: 0 });
      const resolvedTxns: PageResponse<TransactionDto> = await getTransactions({
        state: 'RESOLVED_REFUNDED',
        page: 0,
      });

      let totalRecovered = 0;
      let totalPenaltyTxns = resolvedTxns.totalElements;

      for (const txn of resolvedTxns.content) {
        totalRecovered += txn.penaltyAmountInr ?? 0;
      }

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
    fetchHeroData();
  }, [fetchHeroData]);

  useEffect(() => {
    if (liveMessages.length === 0) return;
    const timer = setTimeout(() => fetchHeroData(), 1500);
    return () => clearTimeout(timer);
  }, [liveMessages.length, fetchHeroData]);

  return (
    <>
      <AnomalyBanner anomalies={anomalies} onDismiss={dismissAnomaly} />
      <HeroCounter
        totalRecovered={heroData.totalRecovered}
        totalPenaltyTransactions={heroData.totalPenaltyTxns}
        totalTransactions={heroData.totalTxns}
      />
      <div className="main-grid">
        <LiveFeed
          liveMessages={liveMessages}
          onSelectTransaction={setSelectedTxnId}
        />
        <BankScorecard liveMessages={liveMessages} />
      </div>
      {selectedTxnId && (
        <TransactionDetail
          txnId={selectedTxnId}
          onClose={() => setSelectedTxnId(null)}
        />
      )}
    </>
  );
}

function App() {
  const { token, isLoading } = useAuth();
  
  if (isLoading) {
    return <div style={{ color: 'var(--text-muted)', padding: '2rem' }}>Loading application...</div>;
  }

  return (
    <div className="app-container">
      {/* ── Unauthenticated Header (Logo only) ─────── */}
      {!token && (
        <header className="app-header">
          <div className="app-header__logo">
            <div className="app-header__icon">₹</div>
            <div>
              <div className="app-header__title">UPI Recovery Dashboard</div>
              <div className="app-header__subtitle">Automated refund tracking & complaint filing</div>
            </div>
          </div>
        </header>
      )}

      <Routes>
        <Route path="/login" element={token ? <Navigate to="/" replace /> : <Login />} />
        <Route path="/signup" element={token ? <Navigate to="/onboarding" replace /> : <SignUp />} />
        <Route path="/*" element={
          <ProtectedRoute>
            <AuthenticatedLayout />
          </ProtectedRoute>
        } />
      </Routes>
    </div>
  );
}

export default App;
