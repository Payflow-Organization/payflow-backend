# ADR-012: Idempotent Consumer via processed_events Table

## Status
Accepted — Week 2

## Context
Kafka provides at-least-once delivery semantics. A consumer may receive the
same event more than once due to network failures, consumer restarts, or
rebalances before an offset is committed. Without deduplication, duplicate
events would produce duplicate audit log entries and incorrect analytics
aggregations — unacceptable in a financial system.

The question is how to detect and discard duplicate events at the consumer level.

## Decision
A `processed_events` table is maintained in PostgreSQL. Before processing any
event, the consumer checks whether `event_id` has already been recorded. If it
has, the event is discarded. If it has not, the event is processed and
`event_id` is inserted in the same database transaction as the consumer's
write operation.

## Alternatives Considered

**No deduplication (at-least-once, accept duplicates)**
- Simplest implementation — no extra table, no extra check
- Duplicate audit log entries on consumer restart
- Duplicate analytics aggregations — balance history and spending totals
  would be incorrect after any consumer failure
- Unacceptable for a financial audit trail

**Exactly-once semantics via Kafka transactions**
- Kafka supports exactly-once delivery when both producer and consumer
  are Kafka brokers (Kafka Streams)
- Does not apply when the consumer writes to PostgreSQL — exactly-once
  only covers Kafka-to-Kafka flows
- Cannot eliminate duplicates at the database write level without
  application-level deduplication

**Idempotent consumer operations (natural idempotency)**
- Design consumer operations to be naturally idempotent — applying the
  same event twice produces the same result as applying it once
- Works for some operations (e.g. SET balance = X) but not for append
  operations (e.g. INSERT INTO audit_logs)
- Audit log entries are append-only by design — natural idempotency
  is not achievable without deduplication

**processed_events table (chosen)**
- `event_id` is checked and inserted atomically with the consumer's write
- Duplicate events are discarded before any side effects occur
- The check and insert happen in the same database transaction — no
  window for a race condition between check and write
- Works for any consumer operation regardless of whether it is naturally
  idempotent
- Table grows over time — old entries can be pruned after a retention
  window that exceeds Kafka's maximum redelivery window

## Rationale
At-least-once delivery is a guarantee, not an edge case. Any production Kafka
consumer will encounter duplicate events during normal operation — consumer
restarts, rebalances, and offset commit failures all trigger redelivery.

Natural idempotency cannot be applied to audit log inserts, which are
append-only by design. Kafka exactly-once semantics do not extend to
PostgreSQL writes. The `processed_events` table is the correct solution
for any consumer that writes to a non-Kafka sink.

The atomicity of the check-and-insert with the consumer's write operation
is critical. If they were separate transactions, a crash between processing
and recording `event_id` would cause the event to be reprocessed on restart.

## Consequences
- `processed_events` table: `event_id VARCHAR(255) PRIMARY KEY, processed_at TIMESTAMPTZ`
- Every consumer checks `processed_events` before processing and inserts
  within the same `@Transactional` block
- `consumer_group` column added so the same `event_id` is independently
  processable by `audit-group` and any future consumer groups
- `processed_events` grows unboundedly without pruning — a nightly cleanup
  job removing entries older than Kafka's retention window is deferred
- Consumer processing is slightly slower due to the extra read and write
  per event — acceptable given the correctness guarantee it provides
- Events that fail after all retries (3 attempts, 500 ms apart via
  `DefaultErrorHandler` + `FixedBackOff`) are published to `transactions.DLT`
  by `DeadLetterPublishingRecoverer` rather than being silently dropped;
  the DLT topic is declared in `KafkaTopicConfig` with the same partition
  count as the source topic; failed events require manual inspection and
  reprocessing — no automatic reprocessing is attempted to avoid masking
  persistent failures (e.g. deserialization bugs, schema mismatches)