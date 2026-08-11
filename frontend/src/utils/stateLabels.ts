/**
 * Merchant-friendly, plain-language labels for backend transaction states.
 *
 * This mapping exists ONLY in the display layer — backend state enum values
 * are never renamed at the API/data level.
 *
 * @see TransactionState.java for the canonical enum.
 */

export interface StateDisplay {
  label: string;
  emoji: string;
  variant: 'success' | 'danger' | 'warning' | 'info' | 'purple' | 'neutral';
  /** True if this state should surface the "Generate Complaint" CTA */
  showComplaintAction: boolean;
}

const STATE_MAP: Record<string, StateDisplay> = {
  INITIATED: {
    label: 'Processing your payment',
    emoji: '🔄',
    variant: 'info',
    showComplaintAction: false,
  },
  SUCCESS: {
    label: 'Payment successful',
    emoji: '✅',
    variant: 'success',
    showComplaintAction: false,
  },
  BUSINESS_DECLINED: {
    label: 'Payment declined',
    emoji: '❌',
    variant: 'danger',
    showComplaintAction: false,
  },
  TECHNICAL_DECLINED: {
    label: 'Bank system error — retrying',
    emoji: '⚠️',
    variant: 'warning',
    showComplaintAction: false,
  },
  DEEMED_APPROVED: {
    label: 'Waiting on bank confirmation',
    emoji: '⏳',
    variant: 'info',
    showComplaintAction: false,
  },
  PENDING_RECONCILIATION: {
    label: 'Under review with bank',
    emoji: '🔍',
    variant: 'info',
    showComplaintAction: false,
  },
  AUTO_REVERSED: {
    label: 'Auto-refunded by bank',
    emoji: '↩️',
    variant: 'success',
    showComplaintAction: false,
  },
  TAT_BREACHED: {
    label: 'Bank missed the deadline',
    emoji: '🚨',
    variant: 'danger',
    showComplaintAction: true,
  },
  PENALTY_ACCRUING: {
    label: 'Payment stuck — bank owes you a refund',
    emoji: '💰',
    variant: 'warning',
    showComplaintAction: true,
  },
  RESOLVED_REFUNDED: {
    label: 'Refund complete',
    emoji: '✅',
    variant: 'success',
    showComplaintAction: false,
  },
  ESCALATED: {
    label: 'Complaint filed',
    emoji: '📋',
    variant: 'purple',
    showComplaintAction: true,
  },
};

const FALLBACK: StateDisplay = {
  label: 'Unknown status',
  emoji: '❓',
  variant: 'neutral',
  showComplaintAction: false,
};

export function getStateDisplay(state: string): StateDisplay {
  return STATE_MAP[state] ?? FALLBACK;
}

/** Quick helper to get just the label */
export function getStateLabel(state: string): string {
  return (STATE_MAP[state] ?? FALLBACK).label;
}

/** Map for state transition display in history */
export function getTransitionLabel(state: string | null): string {
  if (!state) return 'New';
  return (STATE_MAP[state] ?? FALLBACK).label;
}
