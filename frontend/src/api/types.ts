// ── TypeScript types matching backend DTOs exactly ──

export interface TransactionDto {
  txnId: string;
  state: string;
  amountInr: number;
  penaltyAmountInr: number;
  remitterBankId: string | null;
  remitterBankName: string | null;
  beneficiaryBankId: string | null;
  beneficiaryBankName: string | null;
  createdAt: string;
  tatDeadline: string | null;
  penaltyStartAt: string | null;
  resolvedAt: string | null;
  declineCode: string | null;
  orderReference: string | null;
  mlClassification: string | null;
  mlConfidence: number | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number; // current page (0-indexed)
  size: number;
  first: boolean;
  last: boolean;
}

export interface StateTransitionDto {
  fromState: string | null;
  toState: string;
  transitionedAt: string;
  reason: string | null;
}

export interface BankScorecardDto {
  bankId: string;
  bankName: string;
  historicalBdRate: number;
  historicalTdRate: number;
  historicalDeemedApprovedRate: number;
  liveStateCounts: Record<string, number>;
  totalTransactions: number;
  liveReliabilityScore: number;
}

/** WebSocket: state transition message */
export interface LiveFeedMessage {
  txnId: string;
  oldState: string | null;
  newState: string;
  penaltyAmountInr: number;
  bankId: string;
  timestamp: string;
}

/** WebSocket: anomaly message */
export interface AnomalyMessage {
  eventType: 'ANOMALY_FLAGGED';
  bankId: string;
  bankName: string;
  failureRateNow: number;
  historicalBaseline: number;
  affectedCount: number;
  timestamp: string;
}

export type WebSocketMessage = LiveFeedMessage | AnomalyMessage;

export function isAnomalyMessage(msg: WebSocketMessage): msg is AnomalyMessage {
  return 'eventType' in msg && msg.eventType === 'ANOMALY_FLAGGED';
}
