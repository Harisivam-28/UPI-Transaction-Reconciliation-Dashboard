import React, { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getTransactionHistory } from '../services/api';
import type { StateTransition } from '../types';
import { formatDate, getStateLabel, getStateTheme } from '../utils/helpers';
import { ArrowLeft, ShieldCheck } from 'lucide-react';

export const AuditHistory: React.FC = () => {
  const { txnId } = useParams<{ txnId: string }>();
  const [history, setHistory] = useState<StateTransition[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!txnId) return;
    const fetchHistory = async () => {
      try {
        const data = await getTransactionHistory(txnId);
        setHistory(data);
      } catch (error) {
        console.error('Failed to fetch history', error);
      } finally {
        setLoading(false);
      }
    };
    fetchHistory();
  }, [txnId]);

  return (
    <div className="container" style={{ maxWidth: '800px' }}>
      <div style={{ marginBottom: '2rem' }}>
        <Link 
          to="/" 
          style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', color: 'var(--text-muted)', textDecoration: 'none', fontWeight: 500 }}
        >
          <ArrowLeft size={16} /> Back to Dashboard
        </Link>
      </div>

      <header style={{ marginBottom: '2.5rem', borderBottom: '2px solid var(--border-color)', paddingBottom: '1.5rem' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginBottom: '0.5rem' }}>
          <ShieldCheck size={28} color="var(--primary-navy)" />
          <h1 style={{ fontSize: '1.75rem', margin: 0 }}>Audit Trail</h1>
        </div>
        <p style={{ color: 'var(--text-muted)', margin: 0 }}>
          Tamper-evident state transition record for transaction: <br />
          <strong className="mono-data" style={{ color: 'var(--text-main)', fontSize: '1.125rem' }}>{txnId}</strong>
        </p>
      </header>

      {loading ? (
        <p>Loading audit log...</p>
      ) : (
        <div className="card" style={{ padding: '2rem' }}>
          {history.length === 0 ? (
            <p style={{ color: 'var(--text-muted)', textAlign: 'center' }}>No history found.</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
              {history.map((transition, index) => {
                const theme = getStateTheme(transition.toState);
                return (
                  <div key={index} style={{ display: 'flex', gap: '1.5rem', position: 'relative' }}>
                    {/* Timeline line */}
                    {index !== history.length - 1 && (
                      <div style={{ position: 'absolute', top: '24px', left: '11px', bottom: '-32px', width: '2px', backgroundColor: 'var(--border-color)' }} />
                    )}
                    
                    {/* Timeline dot */}
                    <div style={{ width: '24px', height: '24px', borderRadius: '50%', backgroundColor: 'var(--surface-color)', border: '2px solid var(--primary-navy)', zIndex: 1, marginTop: '2px' }} />
                    
                    <div style={{ flex: 1 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: '0.5rem' }}>
                        <h3 style={{ margin: 0, fontSize: '1.125rem', fontFamily: 'var(--font-sans)', color: 'var(--primary-navy)' }}>
                          Transitioned to <span className={`badge badge-${theme}`} style={{ marginLeft: '0.5rem' }}>{getStateLabel(transition.toState)}</span>
                        </h3>
                        <span className="mono-data" style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>
                          {formatDate(transition.transitionedAt)}
                        </span>
                      </div>
                      
                      <div style={{ padding: '1rem', backgroundColor: 'var(--bg-color)', borderRadius: '4px', border: '1px solid var(--border-color)' }}>
                        <p style={{ margin: 0, fontSize: '0.875rem' }}>
                          <strong>From:</strong> <span className="mono-data">{transition.fromState || 'None'}</span> <br />
                          <strong>To:</strong> <span className="mono-data">{transition.toState}</span>
                        </p>
                        {transition.reason && (
                          <p style={{ margin: '0.5rem 0 0 0', fontSize: '0.875rem', color: 'var(--text-muted)' }}>
                            <strong>Reason:</strong> {transition.reason}
                          </p>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
