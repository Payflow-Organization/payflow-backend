# ADR-017: Analytics Direct Query over Kafka Projection

## Status
Accepted — Week 3

## Context
PayFlow's analytics endpoints serve portfolio charts, balance history,
and transaction summaries. Two approaches exist for serving this data:
querying the source tables directly or maintaining a separate projection
built asynchronously from Kafka events.

The `ledger_entries` table is the natural source for analytics — every
balance mutation produces an entry, making it a complete financial history
by design.

## Decision
Query `ledger_entries` directly from analytics endpoints. The table is
a TimescaleDB hypertable partitioned by `created_at` — time-range queries
chunk-prune automatically and a composite index on `(wallet_id, created_at)`
covers wallet-scoped date queries without a full table scan.

## Alternatives Considered

**Kafka projection consumer**
An `AnalyticsProjectionConsumer` consuming transaction events and building
pre-aggregated `analytics_snapshots`. Fast reads at an extreme scale since
queries hit pre-aggregated data rather than raw ledger entries.

The cost: eventual consistency. A user who deposits and immediately checks
their portfolio chart sees stale data until the consumer catches up. For
a financial dashboard this is a meaningful UX and correctness concern.

## Rationale
TimescaleDB's chunk pruning makes direct ledger queries fast enough at
the current scale — the projection's read performance advantage only matters
under an analytic load that PayFlow doesn't approach. The consistency
tradeoff is not worth it: users expect their latest transactions to appear
immediately on the analytics dashboard.

The schema supports adding a projection later without changing the write
path — deferred rather than rejected.

## Consequences
- Analytics endpoints always reflect the latest committed state — no
  eventual consistency on the dashboard
- Query performance bounded by TimescaleDB chunk pruning and the
  composite index — acceptable at the current scale, revisit under a heavy
  analytic load
- No separate consumer, no snapshot table, no reconciliation between
  projection and source — significantly less operational complexity

## Future Considerations
- **TimescaleDB continuous aggregates** — materialized views that refresh
automatically as new hypertable data arrives. Pre-aggregates daily/weekly
summaries without a separate consumer or snapshot table. The natural
next step before reaching for a Kafka projection.

- **Kafka projection consumer** — pre-aggregated snapshots built
asynchronously from transaction events. Higher operational complexity
but decouples analytics read performance from the database entirely.
Only justified at scale where even continuous aggregates can't keep up.