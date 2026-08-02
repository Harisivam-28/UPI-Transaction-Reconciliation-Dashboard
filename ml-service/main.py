"""
UPI Reconciliation — ML Mismatch Classifier Service.

FastAPI service providing transaction mismatch classification
using a scikit-learn DecisionTreeClassifier.

Endpoint:
  POST /classify  — classify a transaction based on 6 features
  GET  /health    — health check
"""

import os
import numpy as np
import joblib
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

# ── Model loading ──────────────────────────────────────────────────────

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(SCRIPT_DIR, "model", "mismatch_classifier.joblib")

_model_data = None


def get_model():
    """Lazy-load the trained model and metadata."""
    global _model_data
    if _model_data is None:
        if not os.path.exists(MODEL_PATH):
            raise RuntimeError(
                f"Model not found at {MODEL_PATH}. "
                "Run train_model.py first."
            )
        _model_data = joblib.load(MODEL_PATH)
    return _model_data


# ── FastAPI app ────────────────────────────────────────────────────────

app = FastAPI(
    title="UPI Reconcile ML Service",
    description="Decision-tree mismatch classifier for transaction outcome prediction",
    version="1.0.0",
)


class ClassificationRequest(BaseModel):
    """Features for transaction mismatch classification.

    These 6 features match the training data schema exactly.
    """
    amount_expected: float = Field(..., description="Expected transaction amount (INR)")
    amount_actual: float = Field(..., description="Actual transaction amount (INR)")
    amount_diff_pct: float = Field(
        0.0,
        description="Percentage difference between expected and actual amounts",
    )
    time_pending_seconds: int = Field(..., description="Seconds the transaction has been pending")
    decline_code_category: str = Field(
        ...,
        description="Decline code category: 'BD', 'TD', or 'none'",
        pattern="^(BD|TD|none)$",
    )
    remitter_bank_historical_td_rate: float = Field(
        ...,
        description="Remitter bank's historical technical decline rate (0.0 - 1.0)",
    )
    is_duplicate_flag: bool = Field(..., description="Whether this transaction is flagged as a duplicate")


class ClassificationResponse(BaseModel):
    """Classification result from the mismatch classifier."""
    classification: str = Field(..., description="Predicted label: stuck_payment|wrong_amount|duplicate_charge|no_mismatch")
    confidence: float = Field(..., description="Probability of the predicted class (0.0 - 1.0)")
    feature_importances: dict[str, float] = Field(
        ...,
        description="Per-feature importance scores from the trained model",
    )


@app.get("/health")
def health_check():
    """Health check endpoint."""
    model_loaded = _model_data is not None or os.path.exists(MODEL_PATH)
    return {
        "status": "healthy",
        "service": "ml-mismatch-classifier",
        "model_available": model_loaded,
    }


@app.post("/classify", response_model=ClassificationResponse)
def classify(request: ClassificationRequest):
    """
    Classify a transaction using the trained decision tree model.

    Converts the 6 input features into the model's expected input format
    (with one-hot encoded decline_code_category), runs prediction, and
    returns the predicted class with confidence score.
    """
    try:
        model_data = get_model()
    except RuntimeError as e:
        raise HTTPException(status_code=503, detail=str(e))

    clf = model_data["model"]
    le = model_data["label_encoder"]
    feature_names = model_data["feature_names"]

    # Build feature vector in the same order as training:
    # [amount_expected, amount_actual, time_pending_seconds,
    #  decline_code_BD, decline_code_TD,
    #  remitter_bank_historical_td_rate, is_duplicate_flag]
    features = np.array([[
        request.amount_expected,
        request.amount_actual,
        request.amount_diff_pct,
        request.time_pending_seconds,
        1.0 if request.decline_code_category == "BD" else 0.0,
        1.0 if request.decline_code_category == "TD" else 0.0,
        request.remitter_bank_historical_td_rate,
        1.0 if request.is_duplicate_flag else 0.0,
    ]])

    # Predict class and probabilities
    prediction = clf.predict(features)[0]
    probabilities = clf.predict_proba(features)[0]
    confidence = float(probabilities[prediction])
    class_label = le.inverse_transform([prediction])[0]

    # Feature importances
    importances = {
        name: round(float(imp), 4)
        for name, imp in zip(feature_names, clf.feature_importances_)
    }

    return ClassificationResponse(
        classification=class_label,
        confidence=round(confidence, 4),
        feature_importances=importances,
    )
