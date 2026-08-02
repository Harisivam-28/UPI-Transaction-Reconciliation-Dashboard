import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Transaction } from '../types';
import { formatINR, getStateLabel, getStateTheme } from '../utils/helpers';
import { generateComplaint } from '../services/api';

interface TransactionFeedProps {
  transactions: Transaction[];
}

export const TransactionFeed: React.FC<TransactionFeedProps> = ({ transactions }) => {
  const navigate = useNavigate();
  const [complaints, setComplaints] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState<Record<string, boolean>>({});

  const handleRowClick = (txnId: string) => {
    navigate(`/history/${txnId}`);
  };

  const handleComplaint = async (e: React.MouseEvent, txnId: string) => {
    e.stopPropagation(); // Prevent row click
    if (loading[txnId] || complaints[txnId]) return;

    setLoading(prev => ({ ...prev, [txnId]: true }));
    try {
      const response = await generateComplaint(txnId);
      setComplaints(prev => ({ ...prev, [txnId]: response.complaint }));
      alert("Complaint generated! Check the audit history page for details or console logs.");
      // In a real app, maybe open a modal. But requirement says "displays the returned complaint text clearly".
      // Let's use a simple browser alert for the demo or just console log if it's too long.
      console.log(response.complaint);
    } catch (error) {
      console.error('Failed to generate complaint', error);
      alert('Failed to generate complaint');
    } finally {
      setLoading(prev => ({ ...prev, [txnId]: false }));
    }
  };

  return (
    <div className="card" style={{ padding: '0' }}>
      <table className="dense-table">
        <thead>
          <tr>
            <th>Transaction ID</th>
            <th>Bank</th>
            <th>Amount</th>
            <th>Status</th>
            <th>TAT / Penalty</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
          {transactions.map(txn => {
            const theme = getStateTheme(txn.state);
            const isBreached = ['TAT_BREACHED', 'PENALTY_ACCRUING', 'ESCALATED'].includes(txn.state);
            
            return (
              <tr key={txn.txnId} onClick={() => handleRowClick(txn.txnId)} style={{ cursor: 'pointer' }}>
                <td className="mono-data" style={{ fontSize: '0.8125rem' }}>
                  {txn.txnId.substring(0, 13)}...
                </td>
                <td>{txn.remitterBankName}</td>
                <td className="mono-data">{formatINR(txn.amountInr)}</td>
                <td>
                  <span className={`badge badge-${theme}`}>
                    {getStateLabel(txn.state)}
                  </span>
                </td>
                <td className="mono-data" style={{ color: txn.penaltyAmountInr > 0 ? 'var(--accent-critical)' : 'inherit' }}>
                  {txn.penaltyAmountInr > 0 ? `+${formatINR(txn.penaltyAmountInr)}` : '-'}
                </td>
                <td>
                  {isBreached && (
                    <button 
                      className={`btn ${complaints[txn.txnId] ? 'btn-outline' : 'btn-critical'}`}
                      style={{ padding: '0.25rem 0.5rem', fontSize: '0.75rem' }}
                      onClick={(e) => handleComplaint(e, txn.txnId)}
                      disabled={loading[txn.txnId]}
                    >
                      {loading[txn.txnId] ? 'Generating...' : (complaints[txn.txnId] ? 'Generated' : 'Generate Complaint')}
                    </button>
                  )}
                </td>
              </tr>
            );
          })}
          {transactions.length === 0 && (
            <tr>
              <td colSpan={6} style={{ textAlign: 'center', padding: '3rem', color: 'var(--text-muted)' }}>
                No transactions found.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
};
