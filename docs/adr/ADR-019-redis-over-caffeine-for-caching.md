# ADR-019: Redis over Caffeine for Caching

## Status
Accepted — Week 3

## Context
PayFlow caches wallet balances and frequently read entities to reduce
database load on the read path. A cache hit bypasses the datasource
routing entirely — neither the primary nor the replica is consulted,
eliminating connection pool pressure and reducing replica lag exposure
on cached reads. 

Two caching options were evaluated: Caffeine (in-process
JVM cache) and Redis (external distributed cache).

## Decision
Use Redis as the cache backend via `spring-boot-starter-data-redis` with
a custom `RedisCacheConfiguration` and `LoggingCacheErrorHandler` for
resilience.

## Alternatives Considered

**Caffeine**
- In-process cache — zero network overhead, simple configuration, no
additional infrastructure.
- Fast and appropriate for single-instance
deployments where cache consistency across nodes is not a concern.

The limitation: cache state lives in the JVM heap. A restart clears
the cache entirely. In a multi-instance deployment each instance maintains
its own independent cache — a write on instance A evicts only instance A's
cache, leaving instance B serving stale data.

**Redis (chosen)**
- External distributed cache — shared across all instances, survives
restarts, and eviction on one instance is visible to all. 
- Production-realistic for any horizontally scaled deployment.

## Rationale
PayFlow is currently single-instance — Caffeine would work. Redis was
chosen because it accurately represents how caching works in a production
distributed system.

The operational overhead of running Redis is minimal — it's already part
of the stack for JWT denylist storage.

## Consequences
- Cache is shared and consistent across instances — no stale reads from
  per-instance cache divergence in a multi-instance deployment
- Network round-trip on every cache operation — negligible at current
  scale, measurable under high throughput
- `LoggingCacheErrorHandler` ensures Redis unavailability degrades
  gracefully — cache misses fall through to the database rather than
  throwing exceptions
- Composite cache keys include `userId` as a discriminator — prevents
  cross-user cache pollution where a wallet read for user A could serve
  cached data to user B

## Future Considerations
Cache-aside with primary read on cache miss would eliminate the replica
lag compounding issue described in
[ADR-016](ADR-016-abstract-routing-datasource-read-write-split.md).
Currently, cache misses hit the replica — routing misses to the primary
would ensure Redis is always populated with fresh data.