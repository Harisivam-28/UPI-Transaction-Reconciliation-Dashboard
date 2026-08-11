import { apiFetch } from './client';
import type { BankScorecardDto } from './types';

export function getBankScorecard(): Promise<BankScorecardDto[]> {
  return apiFetch<BankScorecardDto[]>('/banks/scorecard');
}
