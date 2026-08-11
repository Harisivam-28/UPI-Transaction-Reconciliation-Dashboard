import type { AnomalyMessage } from '../api/types';

interface AnomalyBannerProps {
  anomalies: AnomalyMessage[];
  onDismiss: (index: number) => void;
}

/**
 * Alert banner area for systemic anomaly events (ANOMALY_FLAGGED).
 * Renders one banner per active anomaly, stacked.
 */
export function AnomalyBanner({ anomalies, onDismiss }: AnomalyBannerProps) {
  if (anomalies.length === 0) return null;

  return (
    <div role="alert" aria-label="Bank anomaly alerts">
      {anomalies.map((anomaly, idx) => {
        const pct = (anomaly.failureRateNow * 100).toFixed(1);
        return (
          <div key={`${anomaly.bankId}-${anomaly.timestamp}-${idx}`} className="anomaly-banner">
            <div className="anomaly-banner__icon">🚨</div>
            <div className="anomaly-banner__content">
              <p className="anomaly-banner__title">
                Possible outage at {anomaly.bankName}
              </p>
              <p className="anomaly-banner__detail">
                Failure rate spiked to {pct}% — {anomaly.affectedCount} transactions
                affected. This bank may be experiencing technical difficulties.
              </p>
            </div>
            <button
              className="anomaly-banner__close"
              onClick={() => onDismiss(idx)}
              aria-label={`Dismiss ${anomaly.bankName} alert`}
            >
              ✕
            </button>
          </div>
        );
      })}
    </div>
  );
}
