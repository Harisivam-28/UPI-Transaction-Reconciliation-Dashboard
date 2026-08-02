import React from 'react';
import { useWebSocket } from '../services/WebSocketContext';
import { AlertTriangle } from 'lucide-react';

export const AnomalyBanner: React.FC = () => {
  const { anomalies } = useWebSocket();
  const latestAnomaly = anomalies[0];

  if (!latestAnomaly) return null;

  return (
    <div className="card" style={{ marginBottom: '1.5rem', borderColor: 'var(--accent-anomaly)', backgroundColor: 'var(--accent-anomaly-bg)' }}>
      <div style={{ padding: '1rem', display: 'flex', alignItems: 'center', gap: '1rem' }}>
        <AlertTriangle color="var(--accent-anomaly)" />
        <div>
          <h3 style={{ margin: 0, color: 'var(--text-main)', fontSize: '1rem' }}>
            Systemic Anomaly Flagged: {latestAnomaly.bankName}
          </h3>
          <p style={{ margin: '0.25rem 0 0 0', color: 'var(--text-muted)', fontSize: '0.875rem' }}>
            Failure rate jumped to <strong>{(latestAnomaly.failureRateNow * 100).toFixed(1)}%</strong> 
            {' '}(Baseline: {(latestAnomaly.historicalBaseline * 100).toFixed(1)}%). 
            {' '}<span className="mono-data">{latestAnomaly.affectedCount}</span> transactions affected in the last 60s window.
          </p>
        </div>
      </div>
    </div>
  );
};
