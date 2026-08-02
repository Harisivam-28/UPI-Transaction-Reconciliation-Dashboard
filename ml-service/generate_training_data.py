"""
Synthetic training data generator for UPI mismatch classifier.

Produces 5,000 labeled examples with realistic feature distributions
and 10-15% label noise to force the decision tree to learn actual
boundaries rather than memorising deterministic rules.

Labels: stuck_payment | wrong_amount | duplicate_charge | no_mismatch
"""

import csv
import random
import os

SEED = 42
NUM_SAMPLES = 5000
NOISE_RATE = 0.12  # 12% label-flip rate (midpoint of 10-15%)
TAT_DEADLINE = 40  # simulated seconds threshold for "stuck"

LABELS = ["stuck_payment", "wrong_amount", "duplicate_charge", "no_mismatch"]
DECLINE_CODES = ["BD", "TD", "none"]

OUTPUT_PATH = os.path.join(os.path.dirname(__file__), "training_data.csv")


def generate_sample(rng: random.Random) -> dict:
    """Generate a single training sample with deterministic label, pre-noise."""

    # ── Feature generation ────────────────────────────────────────────────

    amount_expected = round(rng.uniform(100, 100_000), 2)

    # ~20% chance of amount mismatch
    if rng.random() < 0.20:
        delta = round(rng.uniform(1, amount_expected * 0.3), 2)
        amount_actual = round(amount_expected + rng.choice([-1, 1]) * delta, 2)
    else:
        amount_actual = amount_expected

    time_pending_seconds = rng.randint(1, 120)

    # Decline code distribution: ~15% BD, ~15% TD, ~70% none
    roll = rng.random()
    if roll < 0.15:
        decline_code_category = "BD"
    elif roll < 0.30:
        decline_code_category = "TD"
    else:
        decline_code_category = "none"

    remitter_bank_historical_td_rate = round(rng.uniform(0.01, 0.25), 4)

    # ~10% chance of duplicate flag
    is_duplicate_flag = 1 if rng.random() < 0.10 else 0

    # Derived feature: percentage difference between expected and actual
    # This gives the tree a direct signal for amount mismatches rather
    # than requiring it to learn the relationship from two raw values.
    if amount_expected > 0:
        amount_diff_pct = round(
            abs(amount_actual - amount_expected) / amount_expected * 100, 4
        )
    else:
        amount_diff_pct = 0.0

    # ── Deterministic labelling (pre-noise) ────────────────────────────────

    if is_duplicate_flag == 1:
        label = "duplicate_charge"
    elif abs(amount_expected - amount_actual) > 0.01 and decline_code_category == "none":
        label = "wrong_amount"
    elif time_pending_seconds > TAT_DEADLINE and decline_code_category == "none":
        label = "stuck_payment"
    else:
        label = "no_mismatch"

    return {
        "amount_expected": amount_expected,
        "amount_actual": amount_actual,
        "amount_diff_pct": amount_diff_pct,
        "time_pending_seconds": time_pending_seconds,
        "decline_code_category": decline_code_category,
        "remitter_bank_historical_td_rate": remitter_bank_historical_td_rate,
        "is_duplicate_flag": is_duplicate_flag,
        "label": label,
    }


def inject_noise(samples: list[dict], rng: random.Random, rate: float) -> list[dict]:
    """Flip labels for a fraction of samples to inject realistic noise."""
    num_flips = int(len(samples) * rate)
    flip_indices = rng.sample(range(len(samples)), num_flips)

    for idx in flip_indices:
        original = samples[idx]["label"]
        # Pick a different label at random
        candidates = [l for l in LABELS if l != original]
        samples[idx]["label"] = rng.choice(candidates)

    return samples


def main():
    rng = random.Random(SEED)

    print(f"Generating {NUM_SAMPLES} synthetic training samples...")
    samples = [generate_sample(rng) for _ in range(NUM_SAMPLES)]

    # Distribution before noise
    pre_noise = {}
    for s in samples:
        pre_noise[s["label"]] = pre_noise.get(s["label"], 0) + 1
    print(f"Label distribution (pre-noise): {pre_noise}")

    # Inject noise
    samples = inject_noise(samples, rng, NOISE_RATE)

    post_noise = {}
    for s in samples:
        post_noise[s["label"]] = post_noise.get(s["label"], 0) + 1
    print(f"Label distribution (post-noise): {post_noise}")
    print(f"Noise rate: {NOISE_RATE*100:.0f}% ({int(NUM_SAMPLES * NOISE_RATE)} flipped)")

    # Write CSV
    fieldnames = [
        "amount_expected", "amount_actual", "amount_diff_pct",
        "time_pending_seconds", "decline_code_category",
        "remitter_bank_historical_td_rate", "is_duplicate_flag", "label",
    ]

    with open(OUTPUT_PATH, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(samples)

    print(f"Wrote {len(samples)} samples → {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
