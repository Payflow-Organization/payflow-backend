# ADR-016: Redis over Caffeine for Wallet Cache

## Status
Accepted — Week 3

## Context
ADR-003 established that `current_balance` is cached on the `Wallet` entity for O(1) reads,
with the ledger as the authoritative source of truth. The implementation requires a concrete
cache store. Two mainstream options exist for Spring Boot applications: Caffeine (in-process)
and Redis (out-of-process, distributed).

The key question is where the cache lives relative to the application process and what happens
under horizontal scaling or application restart.

## Decision
Redis is used as the cache store, configured as a standalone container. Spring Cache annotations
(`@Cacheable`, `@CacheEvict`) operate against Redis via `spring-boot-starter-data-redis`.
The cache key is `walletId + ':' + userId` — ownership isolation enforced at the key level.

## Alternatives Considered

**Caffeine (in-process cache)**
- Cache lives inside each JVM as a `ConcurrentHashMap`-backed structure
- Sub-microsecond reads — faster than Redis for a single instance
- Cache eviction is local: when instance A processes a deposit and calls `@CacheEvict`,
  only instance A's cache is cleared; instance B continues serving the stale pre-deposit balance
- Under horizontal scaling, every deployed instance maintains its own independent cache
  with no coherence protocol — different users hitting different instances see different balances
- Cache is lost on application restart — first request after restart is always a cache miss
  followed by a DB hit, with no warm-up period
- No external visibility — cannot inspect or flush cache entries without adding instrumentation

**Redis (chosen)**
- Cache lives in a shared, out-of-process store — all application instances read from
  and evict against the same Redis instance
- A deposit on instance A evicts the cache entry from Redis; instance B's next read
  returns the fresh value from the database — no stale reads across instances
- Survives application restarts; cached entries persist across deployments unless explicitly
  evicted or TTL-expired
- Cache state is inspectable via `redis-cli` and monitorable via Prometheus metrics —
  operational visibility without code changes
- Network round-trip adds ~1ms latency over Caffeine's in-process read; acceptable given
  that the alternative is a PostgreSQL query (5–20ms)
- Requires a serialization format: Redis stores bytes, not Java objects. Spring's default
  `JdkSerializationRedisSerializer` uses Java serialization — brittle across class changes
  and unreadable in `redis-cli`. Jackson JSON serialization is configured instead, which
  requires cached types to be JSON-serializable and produces human-readable cache entries

## Rationale
Caffeine is the correct choice for a single-instance application with no multi-instance
deployment requirement. PayFlow's target architecture includes horizontal scaling of the
application tier — a stated goal in the system design. Under that constraint, an in-process
cache is not a cache: it is per-instance local state that produces inconsistent reads.

Redis is the standard distributed cache for production Java services. It solves the
multi-instance coherence problem that Caffeine cannot, at the cost of a network hop that
is still faster than a database query.

Redis demonstrates production-realistic caching patterns — distributed, independently deployed, 
monitorable — as opposed to an embedded cache that is invisible in architecture diagrams.

## Consequences
- `spring-boot-starter-data-redis` added to `pom.xml`
- Redis runs as a sidecar container in `docker-compose.yml` (`redis:8-alpine`)
- Cache key format: `walletId + ':' + userId` — both components required; `walletId` alone
  would allow cross-user cache hits on shared wallet IDs (ownership isolation violated)
- Redis serialization configured to use Jackson JSON (`GenericJacksonJsonRedisSerializer`)
  instead of Java serialization — entries are readable via `redis-cli`, and cache does not
  break when fields are added to `Wallet`; cached types must be Jackson-deserializable
- `@CacheEvict` on every `WalletService.save()` — eviction is synchronous and fires before
  the method returns; no window where a stale entry can be read after a write
- Redis is a soft dependency: `LoggingCacheErrorHandler` (registered in `CacheConfig.errorHandler()`)
  catches all Redis exceptions, logs a warning, increments `payflow.cache.error{operation,cache}`
  and swallows the exception — `@Cacheable` falls through to the database and `@CacheEvict`
  failures are non-fatal; degraded performance, not data incorrectness
- Nightly reconciliation (`ReconciliationService`) compares `current_balance` against summed
  ledger entries to detect any cache/source-of-truth divergence (see ADR-003)
- TTL set to 10 minutes as a bounded-staleness safety net — eviction is the primary
  mechanism, but a Redis timeout or eviction failure would otherwise leave stale entries
  indefinitely; the 10-minute window is accepted as the cost of that guarantee
