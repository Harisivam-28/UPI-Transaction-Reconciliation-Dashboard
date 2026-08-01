"""
UPI Reconciliation — ML Decision Tree Classifier Service.

FastAPI service providing transaction outcome predictions
using a scikit-learn decision tree classifier.
"""

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(
    title="UPI Reconcile ML Service",
    description="Decision-tree classifier for transaction outcome prediction",
    version="0.1.0",
)


class PredictionRequest(BaseModel):
    """Features for transaction outcome prediction."""
    remitter_bank_id: str
    beneficiary_bank_id: str
    amount_inr: float
    decline_code: str | None = None
    current_state: str


class PredictionResponse(BaseModel):
    """Classification result."""
    predicted_state: str
    confidence: float


@app.get("/health")
def health_check():
    """Health check endpoint."""
    return {"status": "healthy", "service": "ml-classifier"}


@app.post("/predict", response_model=PredictionResponse)
def predict(request: PredictionRequest):
    """
    Predict transaction outcome using decision tree classifier.

    TODO: Load trained model and run inference.
    Currently returns a stub response.
    """
    return PredictionResponse(
        predicted_state="PENDING_RECONCILIATION",
        confidence=0.0,
    )
