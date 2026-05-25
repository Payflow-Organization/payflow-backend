# Architecture patterns

## 1. SERIALIZABLE Isolation
All command handlers that mutate money (deposit, withdrawal, transfer) run under `SERIALIABLE` isolation,
scoped deliberately to operations that touch balances. Auth handlers (`LoginCommandHandler`, `RefreshCommandHandler`) run 
under `READ_COMMITTED` because they don't read balances, so the overhead is unjustified here.

`READ_COMMITTED` on money mutations leaves a critical gap; two concurrent withdrawals both read the same balance, both pass the
sufficient check, and both commit, but the problem is that the second silently overdraws the wallet with no error.
`SERIALIZABLE` closes this problem by tracking read/write dependencies across concurrent transactions and aborting one with 
a serialisation failure when a conflict is detected.PostgreSQL implements this via **SSI (Serializable Snapshot Isolation)** rather than traditional 
locking so that uncontested transactions pay no overhead, only actual conflicts trigger an abort.

Under contention, one transaction is aborted due to a serialisation failure.
(expected cost of SSI).  These surface as 
`InsufficientFundsException` at the command handler boundary, and are covered 
by `TransactionConcurrencyTest` - concurrent double-spend attempts must 
always result in exactly one success and one rejection, never two successes.

See [ADR-008](adr/ADR-008-serializable-isolation-scoped-to-credit-debit.md)

## 2. Transactional Outbox
Every event that happens on the domain layer is saved to `outbox_events` in the same database 
transaction as the state change. A scheduled CRON (`OutboxRelay`) picks up 
unpublished events and publishes them to Kafka in a separate `REQUIRES_NEW` 
transaction.

Without this, publishing to Kafka directly after commit introduces a dual write window. A crash between
The commit  and the publish silently drop 
a payment event with no error anywhere. Outbox uses this logic: if the relay 
crashes mid-publish, the event remains in `outbox_events` as `PENDING` and 
will be picked up on the next tick.

Failed events are tracked explicitly. Each event has a `retryCount` and 
`lastError` field. `incrementRetry()` updates both of those in a `REQUIRES_NEW` 
transaction, so a relay crash doesn't roll back the retry state. If the event exceeds the retry threshold, it
transitions to `FAILED`, keeping failure visible in the same DB as the business data. This means it removes routing
to Kafka DLQ, where it would be operationally separate from the ledger. A Kafka DLQ alternative would make sense in a
multi-team setup with dedicated Kafka operation infrastructure.

See [ADR-013](adr/ADR-013-outbox-pattern-over-direct-kafka-publish.md)

## 3. Idempotent Consumer
Kafka has at least once a guarantee on message delivery, so a consumer crash after processing but before committing the offset would
cause redelivery. Without idempotency, a redelivered deposit event credits a wallet twice with no errors produced anywhere.

Every `@KafkaListener` checks `processed_events` before acting on it. A row is inserted with the event ID and a `consumer_group` 
discriminator and ifcinsert succeeds, the event is new, if it throws `DataIntegrityViolationException` 
On the unique constraint, it's a duplicate and gets skipped. The offset is committed only after successful processing and persisting,
never before (committing first without those two would lose vent permanently on crash)

The `consumer_group` discriminator is what makes this scale across multiple Consumers on the same topic. 
 `AuditConsumer` consumes `transaction-events` independently because, without the discriminator, 
one consumer marking an event as processed would block the other from processing it at all.

Optimistic locking here would also be the wrong tool since there is no row versioning, only an insert attempt. The unique constraint 
violation is cheaper and semantically correct: one operation, no retries, no reads.

See [ADR-012](adr/ADR-012-idempotent-consumer-via-processed-events-table.md)

## 4. Double-Entry Ledger
Every transfer produces two complementary `ledger_entries` rows, a DEBIT row with a sender identity, and a CREDIT row with the identity of
a receiver. In contrast to that, deposits and withdrawals record a single entry since PayFlow doesn't model the external bank account;
the external counterpart is outside the boundary.

Because `current_balance` is a cache, to avoid a full double-entry ledger scan, it's updated atomically in the same 
transaction as the ledger entry, never derived on the fly. Either way, the ledger stays the source of truth, with a nightly reconciliation
job that verifies if the cache matches a ledger sum. Any drift means that the atomic update failed somewhere, so it logs a discrepancy, but
never auto-corrects. Automatic correction would mask the root cause, so this way it serves as a point of investigation on the write path that
produced the drift.

See [ADR-015](adr/ADR-015-double-entry-ledger-over-single-Entry.md)

## 5. CQRS
Mutations on balance need `SERIALIZABLE` isolation. It is unnecessary to read the balance like that because doing so 
adds contention without improving accuracy. The natural answer is to split the two paths entirely rather than manage isolation 
per-method inside shared handlers. Command Query Responsibility Segregation makes separation explicit and mechanically 
enforced at the Spring transaction boundary.

Command handlers for handling transactions (`DepositCommandHandler`, `WithdrawCommandHandler`, `TransferCommandHandler`) own invariant enforcement and run under 
`SERIALIZABLE` isolation. Query handlers run under `READ_COMMITTED` with `readOnly=true`, routed to the read replica automatically. 
Accidental mutations are silently dropped rather than rejected because real protection is architectural, not mechanical, which is a known
limitation of this approach.

The handler-per-operation structure enforces single responsibility, so adding a new operation requires a new handler rather than relying on an old one.
An exception to the rule is `Login` because it feels like a read since it just checks credentials, but it also produces side effects (token issuance, audit trail), which makes it a command. 
That distinction determines the specific isolation transaction boundary and isolation level that apply, not just which package the class lives in.

## 6. Read/Write Datasource Split
People check their balance and browse their history far more often than they actually transfer money. 
Without a dual read/write split all of that read traffic hits the same database as every deposit
and withdrawal, forcing one system to serve two very different kinds of work at once.
Query handlers route to a read replica automatically via
`AbstractRoutingDataSource`. The routing key is the current transaction's
`readOnly` flag - `determineCurrentLookupKey()` checks
`TransactionSynchronizationManager.isCurrentTransactionReadOnly()` and
returns either the primary or replica datasource. No annotation beyond
`@Transactional(readOnly=true)` is needed on the query handler side.

The known limitation of this approach is replica lag.  Postgres replication is asynchronous
by default, so a read immediately after a writing may not yet be visible and return stale data
from the replica. All `@Transactional(readOnly=true)` queries are routed to the replica, so
read-your-own-writes flows (e.g. a user browsing their own history right after a transfer)
may be broken. This is an accepted tradeoff for the simplicity of the implementation at the
current scale; selectively routing balance-critical reads back to primary would be the production mitigation.

```java
protected Object determineCurrentLookupKey() {
    return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ? "read" //replica
        : "write"; //primary
}
```

In integration tests, `BaseIntegrationTest` starts a `PostgreSQLContainer` and uses
`@DynamicPropertySource` to register both the write and read datasource properties so
the routing logic is exercised against the same container-backed database setup used by
the application context. `@ServiceConnection` is used in `TestcontainersConfiguration`
for Kafka and Redis, not for the split datasource wiring.

## 7. Analytics via TimescaleDB
Analytics endpoints query the `ledger_entries` table directly rather than having a separate
projection. This is possible because the `ledger_entries` is a TimescaleDB hypertable partitioned by
`created_at`. Hypertable allows time-range queries to chunk-prune automatically, meaning only the relevant 
time partitions are scanned rather than the full table. A composite index on `(wallet_id, created_at)` covers
wallet-scoped date queries.

At extreme scale TimescaleDB continuous aggregates are the natural next
step, they would pre-aggregate daily/weekly summaries and refresh automatically
as new hypertable data arrives, without introducing a separate consumer or
eventual consistency. A Kafka projection consumer would only be justified
if even continuous aggregates can't keep up.

Integration tests use `timescale/timescaledb:latest-pg18` rather than a
plain Postgres image — hypertable creation and chunk-pruning behavior are
exercised against the real extension in CI, not substituted away.

See [ADR-017](adr/ADR-017-analytics-direct-query-over-kafka-projection.md).

## 8. Authentication Token Architecture
Login issues two tokens — a JWT access token valid for 15 minutes and an
opaque refresh token stored as an SHA-256 hash in the database. A single
long-lived JWT would mean either forcing re-login every 15 minutes or
accepting that a stolen token is valid for hours with no way to revoke it.
The split gives both: seamless UX via silent refresh and a short damage
window if the access token is compromised.

The access token carries `userId` and `jti` in the payload. On every
request: signature check first, Redis denylist check second. The denylist
key is the `jti` with TTL matching the token's remaining lifetime — no
cleanup needed, the entry expires naturally with the token.

Refresh tokens are opaque and rotated on every use. If a revoked token
is presented, all tokens for that user are invalidated immediately —
replay attack detection without a separate mechanism.

Logout writes the access token `jti` to Redis and revokes the refresh
token in the database. A Redis failure leaves the access token valid until
natural expiry — accepted given the 15-minute TTL.

Application layer handlers depend on `TokenPort` and `UserPort` interfaces,
not on `JwtService` or Spring Security's `UserDetailsService` directly.
The auth mechanism is replaceable without touching command handlers.

See [ADR-004](adr/ADR-004-stateless-jwt-stateful-refresh-tokens-deffered.md),
[ADR-005](adr/ADR-005-HS256-over-RS256.md).

## 9. Cookie-Based Token Transport

Tokens from section 8 are delivered as HttpOnly, Secure, SameSite=Strict cookies rather than in the response body. The alternative — returning tokens as JSON and storing them in localStorage — means any XSS vulnerability on the page can read and exfiltrate the token directly. HttpOnly removes that possibility at the browser level. JavaScript cannot read the cookie value regardless of what runs on the page.

SameSite=Strict replaces the need for Spring's CSRF token mechanism, which is disabled. The browser refuses to attach the cookie on any request that did not originate from the same site, so a malicious page cannot silently trigger an authenticated action on the user's behalf.

The deployment topology makes this work. The frontend is on Vercel and the backend on Railway — different domains, which would normally make SameSite=Strict block legitimate requests too. It doesn't because the frontend proxies all API calls through Vercel using Next.js rewrites. Axios always targets a relative `/api/v1` URL on the Vercel domain; Vercel forwards the request to Railway server-side. The browser only ever talks to Vercel, so every request is same-site and the cookie is attached correctly. The Vercel proxy is load-bearing for this security model — if the frontend ever bypassed it and called Railway directly, SameSite=Strict would block the cookies and silently break authentication.

See [ADR-020](adr/ADR-020-httponly-cookies-over-response-body-for-token-transport.md).

## 10. Hexagonal Architecture + DDD + Ports

The dependency rule is one-way and enforced by package structure. `domain/` knows nothing outside itself — no Spring, no JPA, no Kafka. `application/` depends only on `domain/`. `infrastructure/` depends on both but nothing depends on `infrastructure/`. This means breaking the rule produces a compile error, not a code review comment.

The boundary between `application/` and `infrastructure/` is a set of port interfaces. `TokenPort`, `UserPort`, `CsvExportPort`, `PdfExportPort`, and `WalletStatementPort` live in `application/port/`. Command handlers depend on these interfaces, not on `JwtService` or Spring's `UserDetailsService` directly. Infrastructure provides the concrete implementations. Swapping the JWT library, the export format, or the database doesn't touch business logic.

This also makes the application layer unit-testable with plain Mockito. Testcontainers only shows up in the infrastructure layer because that's the only layer that actually talks to real external systems.

## 11. Reconciliation

`ReconciliationService` runs at 02:00 every night and performs two independent checks. The first computes `SUM(CREDIT) - SUM(DEBIT)` across all ledger entries. In a correct double-entry system this is always zero, so a non-zero delta means money was created or destroyed somewhere on the write path. The second check compares `wallet.currentBalance` against the ledger-derived sum per wallet, catching cases where the atomic balance update drifted.

Neither check auto-corrects. Automatic correction would hide the bug that caused the drift. Instead `ReconciliationAlertService` is called and Micrometer counters are incremented so the failure is visible in Prometheus. The gauge `payflow.ledger.imbalance` in `LedgerMetrics` tracks the global delta continuously between reconciliation runs, so a drift shows up before 02:00 and not just after.

## 12. Redis Caching

`WalletService.getActiveById` is cached with a composite key of `walletId + ':' + userId`. The userId is in the key deliberately. Caching on walletId alone means a user who somehow obtained someone else's wallet ID could hit a cached entry that belongs to that other user. The composite key makes ownership isolation a property of the cache structure, not something the query has to check.

`save` evicts on the same composite key. Every write clears the entry so the next read goes to the database. The cache only accelerates reads — the ledger is still the source of truth and the nightly reconciliation catches any drift between the cached balance and the ledger sum.

Redis was chosen over Caffeine because it's shared across instances and survives restarts. A Caffeine cache lives in the JVM — a second instance or a rolling restart produces a cold cache that the other instances can't see. Redis failures are caught by `LoggingCacheErrorHandler`, which logs and increments `payflow.cache.error` rather than letting the exception propagate. The app falls back to the database transparently.

See [ADR-019](adr/ADR-019-redis-over-caffeine-for-caching.md), [ADR-003](adr/ADR-003-wallet-balance-cached-ledger-source-of-truth.md).

## 13. Observability

Every command handler records a Micrometer counter and a latency timer. Tags are low-cardinality only: `currency`, `reason`, `command_type`, `path`. Using `wallet_id` or `user_id` as a tag creates an unbounded label set that makes Prometheus scraping expensive and dashboards unreadable.

Two gauges run continuously. `LedgerMetrics` exposes `payflow.ledger.imbalance`, the global `SUM(CREDIT - DEBIT)`, which should always be zero. `OutboxMetrics` exposes `payflow.outbox.pending.size`, the count of unprocessed outbox events, which should drain to zero between relay ticks. A rising pending gauge that isn't draining means the relay is broken or Kafka is unreachable.

Trace context crosses the Kafka boundary manually via the W3C `traceparent` header. Spring Boot auto-instruments HTTP but stops at the Kafka publish. The outbox relay injects the current span into message headers before publishing. `AuditConsumer` extracts it and puts `trace.id` and `span.id` into MDC. Without this, the consumer log has no way to connect back to the HTTP request that triggered it.

## 14. Synchronous Export

`WalletStatementQueryHandler` returns a lazy `Stream<TransactionView>` which gets passed directly to `CsvExportPort` or `PdfExportPort`. The controller writes the stream straight to the HTTP response `OutputStream` so the full result set is never loaded into memory at once.

Export is synchronous because the alternative — async job queue — would need a job store, a polling endpoint, and a download endpoint for what is essentially a single-wallet, single-request operation. That's a lot of infrastructure for a problem that doesn't exist yet. If export ever grew to cover multi-wallet or multi-year ranges where generation takes more than a few seconds, async would be the right move.

See [ADR-018](adr/ADR-018-synchronous-export-over-async-job-queue.md).

## 15. Concurrency Testing Architecture

`TransactionConcurrencyTest` verifies the SERIALIZABLE isolation guarantees under real concurrent load. A `ConcurrencyHarness` wraps two `CountDownLatch` instances — threads signal readiness on the first latch, then all release together when the second fires. Ten threads hitting the same wallet at the same time gives SSI something real to arbitrate. Tests run against a real PostgreSQL container via Testcontainers, not H2, because SSI is a PostgreSQL-specific behavior and can't be faked.

Three scenarios are covered. Concurrent deposits must produce a final balance equal to the sum of successful deposits only. Mixed deposits and withdrawals must not corrupt the balance or go negative. Concurrent transfers must conserve money across both wallets — source plus destination must always equal the starting total. Optimistic and pessimistic locking failures are caught and treated as expected, not as test failures. `InsufficientBalanceException` is also caught in the withdrawal case because it's a valid business rejection, not corruption.

## 16. Kafka Configuration

Kafka runs in KRaft mode with no ZooKeeper. A single topic `transaction-events` has 3 partitions and 3 replicas. One topic was enough because the event volume doesn't justify managing separate topics per event type, and consumers that need to filter can do it by checking the event type field in the payload.

`enable-auto-commit` is false. Offsets are committed manually after processing and persistence succeed. Auto-commit acks on return regardless of what happened, so a crash mid-handler loses the event with no retry.

`KafkaConsumerConfig` registers a `DefaultErrorHandler` with `FixedBackOff(0, 0)` — zero retries at the Kafka level. Retrying here is redundant because the `processed_events` table already handles redelivery via the idempotency check. A message that fails is logged with topic, offset, and payload, the offset is committed, and the partition keeps moving. The failure shows up in logs and Prometheus without blocking anything.