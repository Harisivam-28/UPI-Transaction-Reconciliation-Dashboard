import React from 'react';
import type { Transaction } from '../types';

interface SystemHealthProps {
  transactions: Transaction[];
}

export const SystemHealth: React.FC<SystemHealthProps> = ({ transactions }) => {
  const counts = transactions.reduce((acc, txn) => {
    acc[txn.state] = (acc[txn.state] || 0) + 1;
    return acc;
  }, {} as Record<string, number>);

  const pending = counts['PENDING_RECONCILIATION'] || 0;
  const breached = counts['TAT_BREACHED'] || 0;
  const penalties = counts['PENALTY_ACCRUING'] || 0;
  const resolved = (counts['RESOLVED_REFUNDED'] || 0) + (counts['AUTO_REVERSED'] || 0) + (counts['SUCCESS'] || 0) + (counts['BUSINESS_DECLINED'] || 0);

  return (
    <div className="card" style={{ marginBottom: '1.5rem', padding: '0.75rem 1.5rem', display: 'flex', gap: '1.5rem', alignItems: 'center', backgroundColor: 'var(--primary-navy)', color: 'white' }}>
      <span style={{ fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: '0.05em', color: '#94a3b8' }}>
        System Health
      </span>
      <div style={{ display: 'flex', gap: '1rem', fontSize: '0.875rem' }}>
        <span><strong>{pending}</strong> pending reconciliation</span>
        <span style={{ color: '#94a3b8' }}>·</span>
        <span><strong style={{ color: '#fca5a5' }}>{breached}</strong> breached TAT</span>
        <span style={{ color: '#94a3b8' }}>·</span>
        <span><strong style={{ color: '#fca5a5' }}>{penalties}</strong> accruing penalties</span>
        <span style={{ color: '#94a3b8' }}>·</span>
        <span><strong style={{ color: '#86efac' }}>{resolved}</strong> resolved/terminal</span>
      </div>
    </div>
  );
};
