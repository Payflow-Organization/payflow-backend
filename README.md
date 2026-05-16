# PayFlow Backend ![CI](https://github.com/Payflow-Organization/payflow-backend/actions/workflows/ci.yml/badge.svg) [![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=lakiidev_payflow&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=lakiidev_payflow) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=lakiidev_payflow&metric=coverage)](https://sonarcloud.io/summary/new_code?id=lakiidev_payflow) [![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=lakiidev_payflow&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=lakiidev_payflow) [![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=lakiidev_payflow&metric=sqale_index)](https://sonarcloud.io/summary/new_code?id=lakiidev_payflow) [![Docker](https://img.shields.io/badge/ghcr.io-latest-blue?logo=docker)](https://github.com/orgs/Payflow-Organization/packages/container/package/payflow-backend)

Production-grade payment processing backend. Designed with correctness
and reliability as first-class concerns, so every architectural decision
maps to a concrete failure mode that it does prevent.

## Architecture
Built using a hexagonal architecture with Domain-Driven Design. Aggregates own their invariants, 
the domain layer is free of infrastructure concerns, and every architectural decision maps to a concrete failure mode, which it prevents.

### Key design decisions

**Transactional Outbox** - the outbox mutation is written together with the wallet, or not at all.
Relay is responsible for independently picking unpublished events and pushing them to Kafka. Without this,
a crash between commit and publish causes a silent loss of a payment without any error.

**CQRS** - all transactions that mutate money-related data run under `SERIALIZABLE` isolation.
Reading route uses a replica via `AbstractRoutingDataSource` (`readOnly=true`) - no manual routing needed.
Write path is never blocked on read traffic.

**Idempotent Consumer** - Kafka consumers use `processed_events` with a `consumer_group` discriminator table for deduplication.
Race conditions on concurrent insert are handled via `DataIntegrityViolationException`, not optimistic locking.

**Double-entry ledger** - transfers always debit one wallet and credit another. Deposits and withdrawals save only a record of
a single entry since the app doesn't model the external bank account. `current_balance` is used as a cache 
updated atomically on each mutation and is always reconstructable from 
ledger history. Stored as `BIGINT` cents.

**Analytics** — all analytics endpoints query `ledger_entries` hypertable directly instead of adding async projection. 
The hypertable is partitioned by `created_at` - time-range queries chunk-prune automatically, and a composite index on 
`(wallet_id, created_at)` covers wallet-scoped date queries. 

**JWT denylist** - logout invalidates tokens in Redis with a TTL matching the remaining token lifetime. 
No DB round-trip on every request.

**Observability** - Micrometer instrumentation on every command handler, outbox relay and idempotency service. W3C `traceparent` injected 
into Kafka message headers to allow traces to survive async boundary - a single trace covers the full chain from HTTP request through outbox 
relay into the consumer. ECS structured logging throughout.

## Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25, Spring Boot 4.x |
| Database | PostgreSQL 18 (primary + replica) |
| Messaging | Apache Kafka 3.9 (KRaft) |
| Cache | Redis 8 |
| Time-series | TimescaleDB (`ledger_entries` hypertable) |
| Migrations | Flyway |
| Observability | Micrometer + ECS structured logging |
| CI | GitHub Actions + SonarCloud + JaCoCo | 
| Testing | Testcontainers (real infra, no mocks) |
| Hosting | Railway + Redpand + TigerdataCloud|

## Package structure
The codebase follows a hexagonal architecture with five layers - `api` 
(controllers and DTOs), `application` (command handlers and queries), 
`domain` (entities, aggregates, repository interfaces),
`infrastructure` (Kafka, Redis, JPA adapters, datasource config) and `config` (global 
cross-cutting configuration — Jackson, OpenAPI).

## Running Locally

**With Docker:**
```bash
docker pull ghcr.io/Payflow-Organization/payflow-backend:latest
docker compose up -d
```

**From source:**
```bash
docker compose up -d
./mvnw spring-boot:run
```

Set environment variables as defined in `.env.example`.

Tests use Testcontainers — no manual setup required:

```bash
./mvnw test
```

## Documentation
## Operational Readiness
## Known Limitations
## Frontend
