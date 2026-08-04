import { apiFetch } from './client';
import type { TransactionDto, PageResponse, StateTransitionDto } from './types';

export function getTransactions(params?: {
  state?: string;
  bank_id?: string;
  page?: number;
}): Promise<PageResponse<TransactionDto>> {
  const query = new URLSearchParams();
  if (params?.state) query.set('state', params.state);
  if (params?.bank_id) query.set('bank_id', params.bank_id);
  if (params?.page !== undefined) query.set('page', String(params.page));

  const qs = query.toString();
  return apiFetch<PageResponse<TransactionDto>>(
    `/transactions${qs ? `?${qs}` : ''}`
  );
}

export function getTransactionHistory(
  txnId: string
): Promise<StateTransitionDto[]> {
  return apiFetch<StateTransitionDto[]>(`/transactions/${txnId}/history`);
}

export function generateComplaint(
  txnId: string
): Promise<{ complaint: string }> {
  return apiFetch<{ complaint: string }>(
    `/transactions/${txnId}/generate-complaint`,
    { method: 'POST' }
  );
}
