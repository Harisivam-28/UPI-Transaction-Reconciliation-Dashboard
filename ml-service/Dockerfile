FROM python:3.11-slim

WORKDIR /app

# Install graphviz system package for tree visualization
RUN apt-get update && apt-get install -y --no-install-recommends graphviz && \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Train model during build — bakes the model artifact into the image
RUN python generate_training_data.py && python train_model.py

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
