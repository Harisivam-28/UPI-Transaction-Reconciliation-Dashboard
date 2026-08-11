import { useState, useEffect, useRef } from 'react';
import { secondsUntil } from '../utils/format';

/**
 * Ticks a countdown every second from an ISO deadline string.
 * Returns the remaining seconds (negative means overdue).
 */
export function useCountdown(deadline: string | null | undefined): number | null {
  const [remaining, setRemaining] = useState<number | null>(() =>
    secondsUntil(deadline)
  );
  const intervalRef = useRef<ReturnType<typeof setInterval> | undefined>(undefined);

  useEffect(() => {
    if (!deadline) {
      setRemaining(null);
      return;
    }

    // Compute immediately
    setRemaining(secondsUntil(deadline));

    // Tick every second
    intervalRef.current = setInterval(() => {
      setRemaining(secondsUntil(deadline));
    }, 1000);

    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [deadline]);

  return remaining;
}
