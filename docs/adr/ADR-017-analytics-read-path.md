# ADR-017: Analytics Read Path — TimescaleDB Hypertable over Separate Projection

## Status
Accepted — Week 3

## Context
PayFlow exposes three analytics endpoints: balance history over a date range,
spending by transaction type, and monthly credit/debit summary. Each query
aggregates data from the ledger over a bounded time window.

Three structural questions needed to be answered:

1. **Where does the data come from?** Query `ledger_entries` directly, or maintain a
   separate pre-aggregated projection table updated by Kafka consumers?
2. **How are time-range queries made efficient?** `ledger_entries` grows unboundedly;
   a full table scan per analytics call is not acceptable.
3. **Which datasource handles analytics queries?** The write primary or the read replica?

## Decision
Analytics queries run directly against `ledger_entries` with no intermediate projection.
`ledger_entries` is converted to a TimescaleDB hypertable partitioned by `created_at`
in 1-month chunks. All analytics handlers are `@Transactional(readOnly = true)`, which
routes them to the read replica automatically via `AbstractRoutingDataSource` (ADR-011).

## Alternatives Considered

**Separate analytics projection table (Kafka-updated)**
- `AnalyticsConsumer` listens to `transactions` topic and maintains a pre-aggregated
  `analytics_summary` table — daily totals per wallet, spending category buckets, etc.
- Reads are O(1) against the summary table instead of scanning ledger entries
- Introduces eventual consistency: the projection lags behind the ledger by up to one
  Kafka poll interval; a statement export could reflect outdated balances
- Doubles write load: every transaction writes to the ledger and triggers a projection
  update — two write paths to keep in sync
- Schema changes to aggregation logic require backfill of the entire projection
- No evidence that TimescaleDB chunk-pruned queries are insufficient at current scale —
  premature optimisation

**PostgreSQL partitioned table (manual range partitioning)**
- Achieves chunk pruning without TimescaleDB
- Requires manual DDL for each new partition; no automatic chunk creation
- No built-in time-series query functions (`time_bucket`)
- TimescaleDB is a superset — all PostgreSQL features plus automated chunk management

**TimescaleDB hypertable on `ledger_entries` (chosen)**
- `ledger_entries` is structurally a time-series dataset: append-only, ordered by
  `created_at`, queried almost exclusively by time range and wallet
- `create_hypertable('ledger_entries', 'created_at', chunk_time_interval => INTERVAL '1 month')`
  partitions the table into 1-month chunks automatically; time-range queries only scan
  relevant chunks — effectively O(chunks in range) not O(table)
- `idx_ledger_wallet ON ledger_entries (wallet_id, created_at DESC)` narrows each chunk
  scan to a single wallet
- Ledger remains the single source of truth — no projection lag, no backfill, no sync
- `time_bucket` function available for balance history bucketing by configurable interval
  (`1 day`, `1 hour`) without any application-side aggregation logic

## Rationale
A separate projection exists to answer queries that the source table cannot answer
efficiently. `ledger_entries` with a hypertable and composite index can answer all
current analytics queries efficiently: time-range aggregations over a single wallet
are bounded by chunk count, not total ledger size.

The projection approach introduces eventual consistency — the one property that is
unacceptable for financial statement exports. A user exporting the last 30 days of
transactions expects to see every transaction that has committed, not those that have
also made it through the Kafka pipeline.

TimescaleDB is added as a PostgreSQL extension, not a separate service. The operational
surface is unchanged: same PostgreSQL connection, same Flyway migrations, same JPA
repositories. The hypertable is transparent to JPA — queries use standard JPQL.

## Consequences
- `V17__enable_timescaledb.sql`: `CREATE EXTENSION IF NOT EXISTS timescaledb`
- `V18__recreate_ledger_entries_hypertable.sql`: drops and recreates `ledger_entries`
  with `(id, created_at)` composite PK (TimescaleDB requires the partition column in
  the PK), then calls `create_hypertable`
- `idx_ledger_wallet ON ledger_entries (wallet_id, created_at DESC)` is the hot-path
  index for all per-wallet time-range queries
- `AnalyticsQueryHandler` uses `@Transactional(readOnly = true)` — routes to read replica,
  zero contention with the write primary (see ADR-011)
- `WalletStatementQueryHandler` streams `TransactionView` rows from `ledger_entries` via
  `Stream<TransactionView>` — rows are never loaded into a `List` on the server; memory
  usage is bounded regardless of date range width
- If analytics queries become a bottleneck (large wallets, high fan-out), a Kafka-updated
  projection is the natural next step — an `AnalyticsConsumer` can be added to the
  `transactions` topic as a new consumer group; no publisher changes required and the
  projection schema can be introduced without touching the write path
- TimescaleDB continuous aggregates are available as a future optimisation if query latency
  becomes a concern; not introduced now (YAGNI)
