import { useEffect, useState } from 'react';
import type { BankScorecardDto } from '../api/types';
import { getBankScorecard } from '../api/banks';
import type { LiveFeedMessage } from '../api/types';

interface BankScorecardProps {
  liveMessages: LiveFeedMessage[];
}

export function BankScorecard({ liveMessages }: BankScorecardProps) {
  const [banks, setBanks] = useState<BankScorecardDto[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchScorecard = async () => {
    try {
      const data = await getBankScorecard();
      setBanks(data);
    } catch (err) {
      console.error('Failed to fetch scorecard:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchScorecard();
  }, []);

  // Refresh scorecard when WS messages arrive (debounced)
  useEffect(() => {
    if (liveMessages.length === 0) return;
    const timer = setTimeout(() => fetchScorecard(), 2000);
    return () => clearTimeout(timer);
  }, [liveMessages.length]);

  if (loading) {
    return (
      <aside className="scorecard-section" aria-label="Bank scorecard">
        <h2 className="scorecard-header">Bank Reliability</h2>
        <div className="loading-spinner"><div className="spinner" /></div>
      </aside>
    );
  }

  return (
    <aside className="scorecard-section" aria-label="Bank scorecard">
      <h2 className="scorecard-header">Bank Reliability</h2>
      <div className="scorecard-list">
        {banks.map((bank, idx) => {
          const score = Number(bank.liveReliabilityScore);
          const pct = Math.round(score * 100);
          const barColor = score >= 0.9
            ? 'var(--success)'
            : score >= 0.7
            ? 'var(--warning)'
            : 'var(--danger)';

          return (
            <div key={bank.bankId} className="scorecard-card">
              <div className="scorecard-card__header">
                <span
                  className={`scorecard-card__rank ${idx < 3 ? 'scorecard-card__rank--top' : ''}`}
                >
                  {idx + 1}
                </span>
                <span className="scorecard-card__name">{bank.bankName}</span>
                <span
                  className="scorecard-card__score"
                  style={{ color: barColor }}
                >
                  {pct}%
                </span>
              </div>
              <div className="scorecard-card__bar">
                <div
                  className="scorecard-card__bar-fill"
                  style={{
                    width: `${pct}%`,
                    background: barColor,
                  }}
                />
              </div>
              <div className="scorecard-card__stats">
                <span className="scorecard-stat">
                  Txns: <span className="scorecard-stat__value">{bank.totalTransactions}</span>
                </span>
                <span className="scorecard-stat">
                  Tech fail: <span className="scorecard-stat__value">
                    {(Number(bank.historicalTdRate) * 100).toFixed(1)}%
                  </span>
                </span>
                <span className="scorecard-stat">
                  Deemed: <span className="scorecard-stat__value">
                    {(Number(bank.historicalDeemedApprovedRate) * 100).toFixed(1)}%
                  </span>
                </span>
              </div>
            </div>
          );
        })}

        {banks.length === 0 && (
          <div className="empty-state">
            <div className="empty-state__icon">🏦</div>
            <p className="empty-state__text">No bank data yet</p>
          </div>
        )}
      </div>
    </aside>
  );
}
