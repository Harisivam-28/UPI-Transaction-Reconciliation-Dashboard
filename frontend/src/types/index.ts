export interface Transaction {
  txnId: string;
  state: string;
  amountInr: number;
  penaltyAmountInr: number;
  remitterBankId: string;
  remitterBankName: string;
  beneficiaryBankId: string;
  beneficiaryBankName: string;
  createdAt: string;
  tatDeadline?: string;
  penaltyStartAt?: string;
  resolvedAt?: string;
  declineCode?: string;
  orderReference?: string;
}

export interface StateTransition {
  fromState?: string;
  toState: string;
  transitionedAt: string;
  reason?: string;
}

export interface BankScorecard {
  bankId: string;
  bankName: string;
  historicalBdRate: number;
  historicalTdRate: number;
  historicalDeemedApprovedRate: number;
  liveStateCounts: Record<string, number>;
  totalTransactions: number;
  liveReliabilityScore: number;
}

export interface LiveFeedMessage {
  txnId: string;
  oldState?: string;
  newState: string;
  penaltyAmountInr: number;
  bankId: string;
  timestamp: string;
}

export interface AnomalyMessage {
  eventType: string; // "ANOMALY_FLAGGED"
  bankId: string;
  bankName: string;
  failureRateNow: number;
  historicalBaseline: number;
  affectedCount: number;
  timestamp: string;
}
