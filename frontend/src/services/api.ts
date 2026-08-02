import axios from 'axios';
import type { Transaction, StateTransition, BankScorecard } from '../types';

const API_BASE = '/api';

export const getTransactions = async (page = 0, state?: string, bankId?: string) => {
  const params = new URLSearchParams({ page: page.toString() });
  if (state) params.append('state', state);
  if (bankId) params.append('bank_id', bankId);
  
  const response = await axios.get(`${API_BASE}/transactions?${params.toString()}`);
  return response.data as { content: Transaction[], totalElements: number, totalPages: number };
};

export const getTransactionHistory = async (txnId: string) => {
  const response = await axios.get(`${API_BASE}/transactions/${txnId}/history`);
  return response.data as StateTransition[];
};

export const generateComplaint = async (txnId: string) => {
  const response = await axios.post(`${API_BASE}/transactions/${txnId}/generate-complaint`);
  return response.data as { complaint: string };
};

export const getBankScorecard = async () => {
  const response = await axios.get(`${API_BASE}/banks/scorecard`);
  return response.data as BankScorecard[];
};
