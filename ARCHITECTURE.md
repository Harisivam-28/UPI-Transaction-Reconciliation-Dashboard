# UPI Reconciliation Dashboard — Technical Spec
## Transaction State Machine, TAT/Penalty Engine, Schema, API Contracts

Grounded in RBI Harmonised TAT Circular (RBI/2019-20/67, DPSS.CO.PD No.629/02.01.014/2019-20)
and NPCI's public BD/TD/Deemed-Approved framework.

---

## 1. Transaction States

| State | Meaning | Terminal? |
|---|---|---|
| `INITIATED` | Webhook received, matching in progress | No |
| `SUCCESS` | Matched cleanly against order record | Yes |
| `BUSINESS_DECLINED` (BD) | Customer-side error (wrong PIN, invalid beneficiary, limit exceeded) | Yes |
| `TECHNICAL_DECLINED` (TD) | System-side failure (malformed bank ID, missing exception code) | No — eligible for retry |
| `DEEMED_APPROVED` | Debited, but beneficiary bank sent no online credit confirmation | No |
| `PENDING_RECONCILIATION` | Inside T+1 window, awaiting NPCI batch resolution | No |
| `AUTO_REVERSED` | Resolved via NPCI-style batch reconciliation before/at T+1 | Yes |
| `TAT_BREACHED` | Passed T+1 deadline unresolved | No |
| `PENALTY_ACCRUING` | Beyond T+1, ₹100/day compensation clock running | No |
| `RESOLVED_REFUNDED` | Finally resolved; final penalty amount locked | Yes |
| `ESCALATED` | Unresolved past configurable threshold; complaint auto-drafted | Yes (for prototype scope) |
| `SYSTEMIC_ANOMALY_FLAGGED` | Cross-transaction flag, not a per-txn terminal state — see §5 | N/A |

---

## 2. State Transition Table

| From | Event / Condition | To |
|---|---|---|
| `INITIATED` | Match found, amounts/IDs align | `SUCCESS` |
| `INITIATED` | Decline code in BD set (bad PIN, invalid beneficiary, limit exceeded) | `BUSINESS_DECLINED` |
| `INITIATED` | Decline code in TD set (malformed bank ID, missing exception code) | `TECHNICAL_DECLINED` |
| `INITIATED` | No online credit confirmation from beneficiary bank within X seconds | `DEEMED_APPROVED` |
| `TECHNICAL_DECLINED` | Retry succeeds (≤2 auto-retries, exponential backoff) | `SUCCESS` |
| `TECHNICAL_DECLINED` | Retries exhausted | `PENDING_RECONCILIATION` |
| `DEEMED_APPROVED` | Enters queue | `PENDING_RECONCILIATION` |
| `PENDING_RECONCILIATION` | Resolved at a simulated batch window, before T+1 deadline | `AUTO_REVERSED` |
| `PENDING_RECONCILIATION` | T+1 deadline passes, unresolved | `TAT_BREACHED` |
| `TAT_BREACHED` | Immediately | `PENALTY_ACCRUING` (clock starts at T+2) |
| `PENALTY_ACCRUING` | Resolution event arrives | `RESOLVED_REFUNDED` (penalty locked at resolution time) |
| `PENALTY_ACCRUING` | Exceeds escalation threshold (demo: T+3) with no resolution | `ESCALATED` |

**Batch resolution windows (simulated):** real NPCI auto-reversal runs ~11 PM, 2 AM, 5 AM IST.
For the demo, model 3 batch ticks per simulated day at fixed intervals rather than continuous
resolution — this small detail signals real research depth when you explain it live.

---

## 3. Time Compression for Demo

```
SIMULATED_DAY_SECONDS = 20        // 1 "day" = 20 real seconds (tune to fit pitch length)
T_PLUS_1_DEADLINE      = txn.created_at + 1 * SIMULATED_DAY_SECONDS
PENALTY_START          = T_PLUS_1_DEADLINE + 1 * SIMULATED_DAY_SECONDS   // penalty accrues from "T+2"
ESCALATION_THRESHOLD   = T_PLUS_1_DEADLINE + 2 * SIMULATED_DAY_SECONDS   // demo-only, configurable
BATCH_TICK_INTERVAL    = SIMULATED_DAY_SECONDS / 3   // mimics 11PM/2AM/5AM batch cadence
```

Run a scheduler (setInterval / Spring `@Scheduled`) on `BATCH_TICK_INTERVAL` that sweeps all
`PENDING_RECONCILIATION` transactions and attempts resolution using the bank's real historical
BD/TD probability as the chance of successful auto-reversal at that tick.

---

## 4. Penalty Calculation

```
penalty_days = max(0, floor((resolution_or_now_time - PENALTY_START) / SIMULATED_DAY_SECONDS) + 1)
penalty_amount_inr = penalty_days * 100
```

- No penalty if resolved at or before T+1.
- Penalty accrues per simulated day starting at T+2, consistent with RBI's compensation framework
  (₹100/day for delay beyond the mandated TAT).
- Lock `penalty_amount_inr` permanently once state → `RESOLVED_REFUNDED`.

---

## 5. Systemic Anomaly Detection (cross-transaction)

Not a per-transaction state — a rolling monitor:

```
window = last 60 simulated seconds
for each bank_id:
    failure_rate_now = count(TD or DEEMED_APPROVED, bank_id, window) / count(all txns, bank_id, window)
    if failure_rate_now > (bank.historical_avg_failure_rate * 3):
        flag bank_id as SYSTEMIC_ANOMALY_FLAGGED
        surface banner: "Possible outage at {bank_name} — {N} transactions affected"
```

This distinguishes "one customer's bad luck" from "this bank's rails are down right now" —
mirrors real NPCI-acknowledged outage events.

---

## 6. Core Schema (Postgres)

```sql
CREATE TABLE banks (
    bank_id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    historical_bd_rate NUMERIC(6,4),   -- seeded from real NPCI dataset
    historical_td_rate NUMERIC(6,4),
    historical_deemed_approved_rate NUMERIC(6,4)
);

CREATE TABLE transactions (
    txn_id UUID PRIMARY KEY,
    idempotency_key TEXT UNIQUE NOT NULL,
    remitter_bank_id UUID REFERENCES banks(bank_id),
    beneficiary_bank_id UUID REFERENCES banks(bank_id),
    amount_inr NUMERIC(12,2),
    state TEXT NOT NULL,               -- one of the states in §1
    created_at TIMESTAMPTZ NOT NULL,
    tat_deadline TIMESTAMPTZ,
    penalty_start_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    penalty_amount_inr NUMERIC(10,2) DEFAULT 0,
    decline_code TEXT,                 -- BD/TD reason code if applicable
    order_reference TEXT               -- links to internal merchant order
);

CREATE TABLE state_transitions (
    id BIGSERIAL PRIMARY KEY,
    txn_id UUID REFERENCES transactions(txn_id),
    from_state TEXT,
    to_state TEXT,
    transitioned_at TIMESTAMPTZ NOT NULL,
    reason TEXT
);   -- immutable audit log; also doubles as your "tamper-evident history" talking point

CREATE TABLE webhook_events (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key TEXT NOT NULL,
    payload JSONB,
    received_at TIMESTAMPTZ NOT NULL,
    duplicate_of BIGINT REFERENCES webhook_events(id)  -- null unless deduped
);
```

---

## 7. API Contracts (minimal set to build)

```
POST /api/webhooks/transaction
  body: { idempotency_key, remitter_bank_id, beneficiary_bank_id, amount_inr, order_reference, decline_code? }
  -> 200 { txn_id, state }  |  200 { txn_id, state: "DUPLICATE_IGNORED" }

GET  /api/transactions?state=&bank_id=&page=
  -> list of transactions with current state, countdowns, penalty accrued

GET  /api/transactions/:txn_id/history
  -> full state_transitions audit trail for one transaction

GET  /api/banks/scorecard
  -> per-bank real + live-simulated BD/TD rate, avg resolution time, ranked

POST /api/transactions/:txn_id/generate-complaint
  -> returns pre-filled Ombudsman complaint text (txn_id, dates, circular reference,
     computed penalty) — no LLM required, template-based for reliability

WS   /ws/live-feed
  -> pushes { txn_id, old_state, new_state, penalty_amount_inr, bank_id } on every transition
```

---

## 8. Build Order (maps to your 7-day plan)

1. Schema + seed `banks` table from real NPCI dataset
2. State machine as pure, unit-tested function (no DB/UI yet)
3. Wire state machine into `/api/webhooks/transaction` + idempotency dedup
4. Scheduler for batch-tick resolution sweep + penalty calculation
5. WebSocket push layer
6. Frontend: live feed, countdown badges, penalty counters, bank scorecard
7. Complaint generator (template-based)
8. Systemic anomaly banner
9. Chaos-trigger control panel for your live demo
