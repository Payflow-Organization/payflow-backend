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
always result in exactly one success and one rejection, never two successes. ]

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
to Kafka DLQ where it would be operationally separate from the ledger. A Kafka DLQ alternative would make sense in a
multi-team setup with dedicated Kafka operation infrastructure.

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


