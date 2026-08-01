# UPI Transaction Reconciliation Dashboard

Real-time UPI transaction reconciliation engine with state machine, TAT/penalty tracking,
and systemic anomaly detection — grounded in RBI's Harmonised TAT Circular.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full technical specification including:
- Transaction state machine (11 states, §1-§2)
- Time compression for demo (§3)
- Penalty calculation (§4)
- Systemic anomaly detection (§5)
- Database schema (§6)
- API contracts (§7)

## Tech Stack

| Component | Technology |
|-----------|-----------|
| **Backend** | Spring Boot 3.3 / Java 21 |
| **Database** | PostgreSQL 16 + Flyway migrations |
| **Cache** | Redis 7 (idempotency key cache) |
| **Messaging** | Apache Kafka (KRaft mode) |
| **ML Service** | Python FastAPI + scikit-learn |
| **Observability** | Micrometer + Prometheus |
| **WebSocket** | Spring WebSocket (`/ws/live-feed`) |

## Project Structure

```
├── src/main/java/com/upi/reconcile/
│   ├── UpiReconcileApplication.java       # Entry point
│   ├── domain/                            # State machine, entities, repositories
│   │   ├── TransactionState.java          # 11-state enum
│   │   ├── Transaction.java               # JPA entity
│   │   ├── Bank.java                      # JPA entity
│   │   ├── StateTransition.java           # Audit log entity
│   │   ├── WebhookEvent.java              # Webhook dedup entity
│   │   ├── StateMachine.java              # Pure state transition logic (stub)
│   │   └── *Repository.java              # Spring Data JPA repos
│   ├── ingestion/                         # Kafka consumer/producer
│   ├── scheduler/                         # Batch resolution + penalty engine
│   ├── api/                               # REST controllers + WebSocket
│   ├── ml/                                # ML service HTTP client
│   └── config/                            # Redis, idempotency, time compression
├── src/main/resources/
│   ├── application.yml                    # Config (Postgres/Redis/Kafka/ML)
│   └── db/migration/V1__init_schema.sql   # Flyway migration
├── ml-service/                            # Python FastAPI classifier
│   ├── main.py
│   ├── requirements.txt
│   └── Dockerfile
├── docker-compose.yml                     # Full local stack
├── Dockerfile                             # Multi-stage Spring Boot build
└── ARCHITECTURE.md                        # Technical specification
```

## Running Locally

### Prerequisites

- Docker & Docker Compose (v2)

### One Command Start

```bash
docker compose up --build
```

This spins up **all 5 services**:

| Service | URL | Description |
|---------|-----|-------------|
| **Spring Boot App** | `http://localhost:8080` | REST API + WebSocket |
| **ML Service** | `http://localhost:8000` | FastAPI classifier |
| **PostgreSQL** | `localhost:5432` | Database (upi_reconcile) |
| **Redis** | `localhost:6379` | Idempotency cache |
| **Kafka** | `localhost:9092` | Event streaming |

### Verify Services

```bash
# Spring Boot health
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# ML service health
curl http://localhost:8000/health

# ML service docs (Swagger UI)
open http://localhost:8000/docs
```

### API Endpoints

```
POST /api/webhooks/transaction          # Ingest transaction webhook
GET  /api/transactions?state=&bank_id=  # List transactions (filtered)
GET  /api/transactions/:txnId/history   # Audit trail for a transaction
GET  /api/banks/scorecard               # Bank performance rankings
POST /api/transactions/:txnId/generate-complaint  # Generate Ombudsman complaint
WS   /ws/live-feed                      # Real-time state transition feed
```

### Stop Everything

```bash
docker compose down          # Stop services
docker compose down -v       # Stop + remove data volumes
```

## Development (without Docker)

```bash
# Start infrastructure only
docker compose up postgres redis kafka ml-service

# Run Spring Boot locally
chmod +x gradlew
./gradlew bootRun
```

## Observability

Prometheus metrics are exposed at `/actuator/prometheus`. Connect your Prometheus instance
to `http://localhost:8080/actuator/prometheus` for scraping.
