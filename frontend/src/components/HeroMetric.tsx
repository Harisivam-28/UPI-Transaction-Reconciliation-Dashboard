import React from 'react';
import { useWebSocket } from '../services/WebSocketContext';
import { formatINR } from '../utils/helpers';

export const HeroMetric: React.FC = () => {
  const { totalRecovered } = useWebSocket();

  return (
    <div className="card" style={{ padding: '2rem', textAlign: 'center', backgroundColor: 'var(--surface-color)', marginBottom: '1.5rem', borderTop: '4px solid var(--accent-resolved)' }}>
      <h2 style={{ fontSize: '1.25rem', color: 'var(--text-muted)', marginBottom: '0.5rem', fontWeight: 500, fontFamily: 'var(--font-sans)' }}>
        Total Amount Auto-Recovered
      </h2>
      <div className="mono-data" style={{ fontSize: '3rem', fontWeight: 700, color: 'var(--accent-resolved)', lineHeight: 1 }}>
        {formatINR(totalRecovered)}
      </div>
      <p style={{ marginTop: '0.5rem', color: 'var(--text-muted)', fontSize: '0.875rem' }}>
        from resolved penalties and auto-reversals
      </p>
    </div>
  );
};
