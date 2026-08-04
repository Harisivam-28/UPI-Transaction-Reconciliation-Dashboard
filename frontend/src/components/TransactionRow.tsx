import type { TransactionDto } from '../api/types';
import { StateBadge } from './StateBadge';
import { useCountdown } from '../hooks/useCountdown';
import { formatINR, formatCountdown, shortId } from '../utils/format';
import { getStateDisplay } from '../utils/stateLabels';

interface TransactionRowProps {
  transaction: TransactionDto;
  onClick: () => void;
}

export function TransactionRow({ transaction: txn, onClick }: TransactionRowProps) {
  const countdown = useCountdown(txn.tatDeadline);
  const stateInfo = getStateDisplay(txn.state);
  const isUrgent = txn.state === 'TAT_BREACHED' || txn.state === 'ESCALATED';
  const isPenalty = txn.state === 'PENALTY_ACCRUING';
  const showCountdown = countdown !== null && !['SUCCESS', 'BUSINESS_DECLINED', 'AUTO_REVERSED', 'RESOLVED_REFUNDED', 'ESCALATED'].includes(txn.state);

  let rowClass = 'txn-row';
  if (isUrgent) rowClass += ' txn-row--urgent';
  else if (isPenalty) rowClass += ' txn-row--penalty';

  return (
    <div className={rowClass} onClick={onClick} role="button" tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') onClick(); }}>
      <div className="txn-row__main">
        <span className="txn-row__id">#{shortId(txn.txnId)}</span>
        <div className="txn-row__info">
          <span className="txn-row__amount">{formatINR(txn.amountInr)}</span>
          <span className="txn-row__separator">•</span>
          <span className="txn-row__bank">{txn.remitterBankName ?? 'Unknown bank'}</span>
        </div>
        <StateBadge state={txn.state} />
      </div>

      {showCountdown && countdown !== null && (
        <div className="txn-row__countdown">
          <div className={`txn-row__countdown-value ${
            countdown < 0 ? 'txn-row__countdown-value--danger' : 
            countdown < 10 ? 'txn-row__countdown-value--warning' : ''
          }`}>
            {formatCountdown(countdown)}
          </div>
          <div className="txn-row__countdown-label">
            {countdown < 0 ? 'overdue' : 'until deadline'}
          </div>
        </div>
      )}

      {(txn.penaltyAmountInr ?? 0) > 0 && (
        <div className="txn-row__penalty">
          +{formatINR(txn.penaltyAmountInr)} penalty
        </div>
      )}
    </div>
  );
}
