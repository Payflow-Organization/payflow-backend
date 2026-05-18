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
Kafka has at least once guarantee on message delivery, so a consumer crash after processing but before committing the offset would
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
People check their balance and browse their history far more often than they actually transfer money. Without a dual read/write split all of their read traffic hits the same database as every deposit 
and withdrawal. This makes read traffic and write traffic compete for the same database resources, even though reads do not need anything from the write path to do their job correctly. Query handlers route to a read replica 
automatically via `AbstractRoutingDataSource`. The routing key is the current transaction's `readOnly` flag - `determineCurrentLookupKey()` checks `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` 
and returns either the primary or replica datasource. No annotation beyond `@Transactional(readOnly=true)` is needed on the query handler side.
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

