"""
Training pipeline for UPI mismatch classifier.

Trains a scikit-learn DecisionTreeClassifier (max_depth=5) on synthetic
data, evaluates on a held-out test set, and exports:
  1. model/mismatch_classifier.joblib   — serialized model for FastAPI
  2. model/decision_tree.png            — graphviz tree visualization
  3. model/feature_importance.png       — feature importance bar chart
"""

import os
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.tree import DecisionTreeClassifier, export_graphviz
from sklearn.metrics import classification_report
from sklearn.preprocessing import LabelEncoder
import joblib
import subprocess

# Paths relative to this script
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_PATH = os.path.join(SCRIPT_DIR, "training_data.csv")
MODEL_DIR = os.path.join(SCRIPT_DIR, "model")

FEATURE_COLS = [
    "amount_expected",
    "amount_actual",
    "amount_diff_pct",
    "time_pending_seconds",
    "decline_code_BD",
    "decline_code_TD",
    "remitter_bank_historical_td_rate",
    "is_duplicate_flag",
]

LABEL_COL = "label"


def load_and_prepare(path: str) -> tuple[pd.DataFrame, np.ndarray, list[str]]:
    """Load CSV, one-hot encode decline_code_category, return X, y, class_names."""
    df = pd.read_csv(path)

    # One-hot encode decline_code_category → decline_code_BD, decline_code_TD
    df["decline_code_BD"] = (df["decline_code_category"] == "BD").astype(int)
    df["decline_code_TD"] = (df["decline_code_category"] == "TD").astype(int)

    X = df[FEATURE_COLS].values
    y_raw = df[LABEL_COL].values

    # Encode string labels to integers
    le = LabelEncoder()
    y = le.fit_transform(y_raw)

    return df, X, y, le


def train_and_evaluate(X: np.ndarray, y: np.ndarray, le: LabelEncoder):
    """Train DecisionTreeClassifier, print evaluation, return fitted model."""
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.20, random_state=42, stratify=y
    )

    clf = DecisionTreeClassifier(
        max_depth=5,
        random_state=42,
        min_samples_leaf=10,
        class_weight="balanced",
    )
    clf.fit(X_train, y_train)

    y_pred = clf.predict(X_test)

    print("\n" + "=" * 70)
    print("  Classification Report (held-out 20% test set)")
    print("=" * 70)
    print(classification_report(y_test, y_pred, target_names=le.classes_))

    print("\n" + "=" * 70)
    print("  Feature Importances")
    print("=" * 70)
    for name, imp in sorted(
        zip(FEATURE_COLS, clf.feature_importances_), key=lambda x: -x[1]
    ):
        bar = "█" * int(imp * 50)
        print(f"  {name:40s} {imp:.4f}  {bar}")

    print(f"\n  Training samples: {len(X_train)}")
    print(f"  Test samples:     {len(X_test)}")
    print(f"  Tree depth:       {clf.get_depth()}")
    print(f"  Tree leaves:      {clf.get_n_leaves()}")
    print("=" * 70 + "\n")

    return clf


def export_model(clf: DecisionTreeClassifier, le: LabelEncoder):
    """Export model to joblib."""
    os.makedirs(MODEL_DIR, exist_ok=True)
    model_path = os.path.join(MODEL_DIR, "mismatch_classifier.joblib")
    metadata = {
        "model": clf,
        "label_encoder": le,
        "feature_names": FEATURE_COLS,
        "class_names": list(le.classes_),
    }
    joblib.dump(metadata, model_path)
    print(f"✅ Model saved → {model_path}")


def export_tree_visualization(clf: DecisionTreeClassifier, le: LabelEncoder):
    """Export decision tree as PNG via graphviz."""
    dot_path = os.path.join(MODEL_DIR, "decision_tree.dot")
    png_path = os.path.join(MODEL_DIR, "decision_tree.png")

    export_graphviz(
        clf,
        out_file=dot_path,
        feature_names=FEATURE_COLS,
        class_names=list(le.classes_),
        filled=True,
        rounded=True,
        proportion=True,
        impurity=True,
        special_characters=True,
        fontname="Helvetica",
    )

    try:
        subprocess.run(
            ["dot", "-Tpng", "-Gdpi=200", dot_path, "-o", png_path],
            check=True,
            capture_output=True,
        )
        print(f"✅ Tree visualization → {png_path}")
    except FileNotFoundError:
        print("⚠️  graphviz `dot` not found — skipping tree PNG (install graphviz)")
    except subprocess.CalledProcessError as e:
        print(f"⚠️  graphviz export failed: {e.stderr.decode()}")


def export_feature_importance_chart(clf: DecisionTreeClassifier):
    """Export feature importance bar chart as PNG via matplotlib."""
    # Import matplotlib lazily to avoid issues in environments without display
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    png_path = os.path.join(MODEL_DIR, "feature_importance.png")

    importances = clf.feature_importances_
    indices = np.argsort(importances)[::-1]
    sorted_names = [FEATURE_COLS[i] for i in indices]
    sorted_importances = importances[indices]

    # Modern dark theme
    plt.style.use("dark_background")
    fig, ax = plt.subplots(figsize=(10, 6))

    # Gradient-ish bar colors
    colors = plt.cm.plasma(np.linspace(0.2, 0.85, len(sorted_names)))

    bars = ax.barh(
        range(len(sorted_names)),
        sorted_importances,
        color=colors,
        edgecolor="white",
        linewidth=0.5,
        height=0.6,
    )

    ax.set_yticks(range(len(sorted_names)))
    ax.set_yticklabels(sorted_names, fontsize=11, fontweight="medium")
    ax.set_xlabel("Importance", fontsize=12, fontweight="bold")
    ax.set_title(
        "UPI Mismatch Classifier — Feature Importances",
        fontsize=14,
        fontweight="bold",
        pad=15,
    )
    ax.invert_yaxis()

    # Add value labels on bars
    for bar, val in zip(bars, sorted_importances):
        ax.text(
            bar.get_width() + 0.005,
            bar.get_y() + bar.get_height() / 2,
            f"{val:.3f}",
            va="center",
            fontsize=10,
            color="white",
        )

    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.spines["bottom"].set_color("#555")
    ax.spines["left"].set_color("#555")
    ax.tick_params(colors="#ccc")
    ax.xaxis.label.set_color("#ccc")

    plt.tight_layout()
    plt.savefig(png_path, dpi=200, bbox_inches="tight", facecolor=fig.get_facecolor())
    plt.close()

    print(f"✅ Feature importance chart → {png_path}")


def main():
    if not os.path.exists(DATA_PATH):
        print(f"Training data not found at {DATA_PATH}")
        print("Run generate_training_data.py first.")
        return

    print("Loading training data...")
    df, X, y, le = load_and_prepare(DATA_PATH)

    clf = train_and_evaluate(X, y, le)

    os.makedirs(MODEL_DIR, exist_ok=True)
    export_model(clf, le)
    export_tree_visualization(clf, le)
    export_feature_importance_chart(clf)

    print("\n🎯 Training pipeline complete. Model and artifacts exported to model/")


if __name__ == "__main__":
    main()
