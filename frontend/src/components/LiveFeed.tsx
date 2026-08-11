import { useEffect, useState, useCallback } from 'react';
import type { TransactionDto, PageResponse, LiveFeedMessage } from '../api/types';
import { getTransactions } from '../api/transactions';
import { TransactionRow } from './TransactionRow';

interface LiveFeedProps {
  liveMessages: LiveFeedMessage[];
  onSelectTransaction: (txnId: string) => void;
}

const STATE_FILTERS = [
  { value: '', label: 'All' },
  { value: 'PENALTY_ACCRUING', label: 'Stuck payments' },
  { value: 'TAT_BREACHED', label: 'Deadline missed' },
  { value: 'PENDING_RECONCILIATION', label: 'Under review' },
  { value: 'ESCALATED', label: 'Complaint filed' },
  { value: 'RESOLVED_REFUNDED', label: 'Refunded' },
  { value: 'SUCCESS', label: 'Successful' },
];

export function LiveFeed({ liveMessages, onSelectTransaction }: LiveFeedProps) {
  const [transactions, setTransactions] = useState<TransactionDto[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [stateFilter, setStateFilter] = useState('');
  const [loading, setLoading] = useState(true);

  const fetchTransactions = useCallback(async () => {
    try {
      setLoading(true);
      const params: { state?: string; page: number } = { page };
      if (stateFilter) params.state = stateFilter;
      const data: PageResponse<TransactionDto> = await getTransactions(params);
      setTransactions(data.content);
      setTotalPages(data.totalPages);
      setTotalElements(data.totalElements);
    } catch (err) {
      console.error('Failed to fetch transactions:', err);
    } finally {
      setLoading(false);
    }
  }, [page, stateFilter]);

  // Initial fetch + refetch on filter/page change
  useEffect(() => {
    fetchTransactions();
  }, [fetchTransactions]);

  // Re-fetch when new WebSocket messages arrive (debounced)
  useEffect(() => {
    if (liveMessages.length === 0) return;
    const timer = setTimeout(() => fetchTransactions(), 500);
    return () => clearTimeout(timer);
  }, [liveMessages.length, fetchTransactions]);

  return (
    <section className="feed-section" aria-label="Transaction feed">
      <div className="feed-header">
        <h2 className="feed-header__title">Live Transactions</h2>
        <span className="feed-header__count">
          {totalElements} total
        </span>
      </div>

      <div className="feed-filters" role="group" aria-label="Filter transactions">
        {STATE_FILTERS.map((f) => (
          <button
            key={f.value}
            className={`feed-filter ${stateFilter === f.value ? 'feed-filter--active' : ''}`}
            onClick={() => { setStateFilter(f.value); setPage(0); }}
          >
            {f.label}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="loading-spinner">
          <div className="spinner" />
        </div>
      ) : transactions.length === 0 ? (
        <div className="empty-state">
          <div className="empty-state__icon">📭</div>
          <p className="empty-state__text">
            No transactions yet. Send a webhook to get started.
          </p>
        </div>
      ) : (
        <>
          <div className="feed-list">
            {transactions.map((txn) => (
              <TransactionRow
                key={txn.txnId}
                transaction={txn}
                onClick={() => onSelectTransaction(txn.txnId)}
              />
            ))}
          </div>

          <div className="feed-pagination">
            <button
              className="feed-pagination__btn"
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
            >
              ← Previous
            </button>
            <span className="feed-pagination__info">
              Page {page + 1} of {Math.max(1, totalPages)}
            </span>
            <button
              className="feed-pagination__btn"
              disabled={page >= totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
            >
              Next →
            </button>
          </div>
        </>
      )}
    </section>
  );
}
