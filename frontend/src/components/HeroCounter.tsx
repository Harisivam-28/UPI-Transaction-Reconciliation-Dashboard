import { useEffect, useState, useRef } from 'react';

interface HeroCounterProps {
  totalRecovered: number;
  totalPenaltyTransactions: number;
  totalTransactions: number;
}

/**
 * Hero section — visually dominant animated counter showing
 * total penalty amount recovered by the system.
 */
export function HeroCounter({
  totalRecovered,
  totalPenaltyTransactions,
  totalTransactions,
}: HeroCounterProps) {
  const [displayed, setDisplayed] = useState(0);
  const prevRef = useRef(0);

  // Animate the counter when totalRecovered changes
  useEffect(() => {
    const start = prevRef.current;
    const end = totalRecovered;
    const duration = 1200;
    const startTime = performance.now();

    function animate(now: number) {
      const elapsed = now - startTime;
      const progress = Math.min(elapsed / duration, 1);
      // Ease-out cubic
      const eased = 1 - Math.pow(1 - progress, 3);
      setDisplayed(Math.round(start + (end - start) * eased));

      if (progress < 1) {
        requestAnimationFrame(animate);
      } else {
        prevRef.current = end;
      }
    }

    requestAnimationFrame(animate);
  }, [totalRecovered]);

  const formattedAmount = new Intl.NumberFormat('en-IN').format(displayed);

  return (
    <section className="hero-counter" aria-label="Recovery summary">
      <p className="hero-counter__label">Total Amount Auto-Recovered</p>
      <p className="hero-counter__amount" aria-live="polite">
        <span className="hero-counter__currency">₹</span>
        {formattedAmount}
      </p>
      <p className="hero-counter__subtitle">
        Penalties collected from banks on your behalf, per RBI mandate
      </p>
      <div className="hero-counter__stats">
        <div className="hero-stat">
          <span className="hero-stat__value">{totalPenaltyTransactions}</span>
          <span className="hero-stat__label">Recoveries made</span>
        </div>
        <div className="hero-stat">
          <span className="hero-stat__value">{totalTransactions}</span>
          <span className="hero-stat__label">Total transactions</span>
        </div>
        <div className="hero-stat">
          <span className="hero-stat__value">₹100/day</span>
          <span className="hero-stat__label">RBI penalty rate</span>
        </div>
      </div>
    </section>
  );
}
