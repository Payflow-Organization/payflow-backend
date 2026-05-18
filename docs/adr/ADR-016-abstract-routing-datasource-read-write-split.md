# ADR-016: AbstractRoutingDataSource Read/Write Split

## Status
Accepted - Week 3

## Context
PayFlow's read and write workloads have fundamentally different
characteristics. Balance mutations require `SERIALIZABLE` isolation and
hit the primary. Analytics queries, transaction history, and wallet reads
are read-only and don't need the same isolation overhead. With a single
datasource, all read traffic competes for the same connection pool as
writing traffic - adding latency to the writing path under load.

The handler separation already distinguishes commands from queries at
the application layer. The datasource split makes that separation
mechanical at the infrastructure layer too. 

## Decision
Implement `AbstractRoutingDataSource` to route `@Transactional(readOnly=true)`
queries to a read replica and all writes to the primary. The routing key
is `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` -
no annotation beyond `@Transactional(readOnly=true)` is needed on the
query handler side.

## Alternatives Considered

**Single datasource**
- Simple, no routing complexity, no replica lag. 
- Acceptable at low traffic but doesn't scale 
- Read and write workloads compete for the same 
  connection pool indefinitely

**Manual datasource selection per endpoint**
- Explicit control over which endpoints hit primary vs replica
- More granular but requires every developer to make the routing
  decision - easy to get wrong and hard to audit.

## Rationale
`AbstractRoutingDataSource` makes the routing automatic and consistent
with the existing CQRS boundary. Any handler annotated
`@Transactional(readOnly=true)` routes to the replica without additional
decisions from the developer. The routing logic is infrastructure - it
belongs in infrastructure config, not in every query handler.

## Consequences
- Read traffic offloaded to replica, write connection pool uncontested
- Replica lag introduces eventual consistency on the read path -
  read-your-own-writes flows may return stale data immediately after
  a mutation. Accepted at the current scale.
- `@ServiceConnection` is incompatible with the split datasource
  configuration — Testcontainers setup requires `@DynamicPropertySource`
  with manual property registration for both datasource paths
- Both write and read datasource URLs point to the same container in
  integration tests — replication behavior is not tested, routing logic is
- Cache eviction on every command (`@CacheEvict`) clears stale values
  on writes, but a cache miss immediately after eviction may populate
  Redis with a stale replica read — later reads serve that stale
  value until the next write triggers eviction again. Replica lag and
  cache TTL compound rather than cancel out. See [Future Considerations](#future-considerations)
  for mitigations.

## Future Considerations
- **Cache-aside with primary read on cache miss** — on a cache miss after
eviction, route the repopulation read to the primary rather than the
replica. The fresh value is cached and subsequent reads serve it from
Redis. No CQRS boundary crossing, no coupling between command handlers
and the read model shape. More complex to implement, but more consistent.

- **`@CachePut` on command handlers** — write the correct value directly
into Redis on every mutation, bypassing the replica entirely on the next
read. Cleaner consistency guarantee but requires command handlers to know
the read model shape, crossing the CQRS boundary. Deferred for that reason.