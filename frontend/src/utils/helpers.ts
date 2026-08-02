export const formatINR = (amount: number) => {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0
  }).format(amount);
};

export const formatDate = (dateStr: string) => {
  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'medium'
  }).format(new Date(dateStr));
};

export const getStateLabel = (state: string) => {
  switch (state) {
    case 'INITIATED': return 'Processing';
    case 'SUCCESS': return 'Successful';
    case 'BUSINESS_DECLINED': return 'Customer Error (Declined)';
    case 'TECHNICAL_DECLINED': return 'Technical Issue (Retrying)';
    case 'DEEMED_APPROVED': return 'Awaiting Bank Credit';
    case 'PENDING_RECONCILIATION': return 'In Batch Processing';
    case 'AUTO_REVERSED': return 'Resolved Automatically';
    case 'TAT_BREACHED': return 'Deadline Missed (Unresolved)';
    case 'PENALTY_ACCRUING': return 'Stuck - Bank owes refund';
    case 'RESOLVED_REFUNDED': return 'Refunded with Penalty';
    case 'ESCALATED': return 'Escalated to Ombudsman';
    default: return state.replace(/_/g, ' ');
  }
};

export const getStateTheme = (state: string) => {
  if (['TAT_BREACHED'].includes(state)) return 'warning';
  if (['PENALTY_ACCRUING', 'ESCALATED'].includes(state)) return 'critical';
  if (['AUTO_REVERSED', 'RESOLVED_REFUNDED'].includes(state)) return 'resolved';
  return 'neutral';
};
