import React, { useEffect, useState } from 'react';
import { getBankScorecard } from '../services/api';
import type { BankScorecard } from '../types';

export const Scorecard: React.FC = () => {
  const [scorecards, setScorecards] = useState<BankScorecard[]>([]);

  useEffect(() => {
    const fetchScorecards = async () => {
      try {
        const data = await getBankScorecard();
        setScorecards(data);
      } catch (error) {
        console.error('Failed to fetch scorecard', error);
      }
    };
    
    fetchScorecards();
    // In a real app, we might poll this or get updates via WS. For now, fetch once.
    const interval = setInterval(fetchScorecards, 10000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="card" style={{ padding: '1.5rem', height: '100%' }}>
      <h3 style={{ marginBottom: '1.5rem', fontSize: '1.125rem' }}>Bank Reliability Scorecard</h3>
      <div className="dense-table" style={{ margin: '-1.5rem', width: 'calc(100% + 3rem)' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th style={{ padding: '1rem 1.5rem' }}>Bank Name</th>
              <th style={{ padding: '1rem 1.5rem', textAlign: 'right' }}>Volume</th>
              <th style={{ padding: '1rem 1.5rem', textAlign: 'right' }}>Reliability</th>
            </tr>
          </thead>
          <tbody>
            {scorecards.map((bank) => (
              <tr key={bank.bankId}>
                <td style={{ padding: '0.75rem 1.5rem', borderBottom: '1px solid var(--border-color)' }}>
                  {bank.bankName}
                </td>
                <td style={{ padding: '0.75rem 1.5rem', textAlign: 'right', borderBottom: '1px solid var(--border-color)' }} className="mono-data">
                  {bank.totalTransactions.toLocaleString()}
                </td>
                <td style={{ padding: '0.75rem 1.5rem', textAlign: 'right', borderBottom: '1px solid var(--border-color)' }}>
                  <span className={`badge ${bank.liveReliabilityScore < 0.95 ? 'badge-warning' : 'badge-neutral'}`}>
                    {(bank.liveReliabilityScore * 100).toFixed(2)}%
                  </span>
                </td>
              </tr>
            ))}
            {scorecards.length === 0 && (
              <tr>
                <td colSpan={3} style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-muted)' }}>
                  Loading scorecard data...
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};
