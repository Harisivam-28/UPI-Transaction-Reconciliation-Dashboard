import { getStateDisplay } from '../utils/stateLabels';

interface StateBadgeProps {
  state: string;
}

export function StateBadge({ state }: StateBadgeProps) {
  const display = getStateDisplay(state);

  return (
    <span className={`state-badge state-badge--${display.variant}`}>
      <span>{display.emoji}</span>
      <span>{display.label}</span>
    </span>
  );
}
