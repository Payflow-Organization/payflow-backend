# Implementation Notes

Smaller decisions that don't warrant a full ADR but would trip up someone new to the codebase.

---

## Security

**`SecurityContextHolder.MODE_INHERITABLETHREADLOCAL`**
Set in `SecurityConfig` via `@PostConstruct`. The default `MODE_THREADLOCAL` doesn't propagate the security context to child threads. Without this, any async operation spawned from a request thread loses the authenticated principal mid-execution.

**Refresh tokens stored as SHA-256 hash, never raw**
`RefreshTokenService` hashes with `sha256Hex` before persisting. If the `refresh_tokens` table is compromised, the raw tokens are not recoverable. The raw token only exists in memory during the request and in the HttpOnly cookie on the client.

**`revoked = false` uses `@Builder.Default`**
Without `@Builder.Default`, Lombok's builder leaves `boolean` fields as `false` by default anyway — but for `Boolean` (boxed) it would be `null`, which would silently bypass revocation checks. The annotation makes the intent explicit and safe if the type ever changes.

**`CHAR(64)` for SHA-256 hash column**
A SHA-256 hex digest is always exactly 64 characters. `CHAR(64)` rather than `VARCHAR(64)` avoids per-row length metadata and padding overhead. Fixed-length columns also allow the DB to optimize storage layout.

**`BCryptPasswordEncoder(12)` — cost factor 12**
Cost factor 12 is roughly 300ms per hash on commodity hardware. Lower is too fast to meaningfully slow brute force; higher would make every login noticeably slow. 12 is the commonly accepted balance between security and UX.

**`Math.max(remainingTtl, 0)` guard on logout**
The logout denylist entry TTL is the token's remaining lifetime. If the token is already expired at the moment of logout, the remaining TTL would be negative — Redis rejects negative TTLs. The `Math.max` clamp prevents a `DataAccessException` being thrown at logout for an already-expired token.

**`// NOSONAR` on `@Transactional` self-call in `RefreshTokenService`**
`RefreshTokenService.rotate()` calls its own `revoke()` method, which has `@Transactional(propagation = REQUIRES_NEW)`. Self-invocation bypasses the AOP proxy so `REQUIRES_NEW` silently does nothing. Extracting `revoke()` to a separate bean just to satisfy the proxy would be a shallow module — the interface is as complex as the implementation, adding indirection without adding value. Self-injection (`@Autowired private RefreshTokenService self`) solves the proxy issue but makes the dependency graph misleading. The `// NOSONAR` documents the deliberate bypass instead of hiding it.

---

## Caching

**Composite cache key `walletId + ':' + userId`**
`walletId` alone is not safe — a user who knows another wallet's ID could hit a cached entry that belongs to someone else. The colon separator is intentional: UUIDs contain only hex and hyphens, so a colon is unambiguous as a delimiter.

**Cache cleared after every test in `BaseIntegrationTest`**
`@AfterEach` calls `cacheManager.getCache(name).clear()` for all caches. Without this, a cached wallet from one test leaks into the next and produces false positives on balance assertions.

**`disableCachingNullValues()` in `RedisCacheConfiguration`**
`getActiveById` throws on a missing wallet rather than returning null, so null should never be cached. The call is defensive: if any code path ever returns null from a `@Cacheable` method, Spring would cache that null by default and subsequent callers would receive it instead of hitting the DB. Disabling null caching makes the cache only ever hold real values.

**`GenericJacksonJsonRedisSerializer` over deprecated alternatives**
`Jackson2JsonRedisSerializer` is deprecated in Spring Data Redis in favor of `GenericJacksonJsonRedisSerializer`, and `tools.jackson` package naming reflects Jackson 3.x as bundled in Spring Boot 4. The serializer is constructed with a custom `ObjectMapper` that enables `DefaultTyping.NON_FINAL` so concrete types survive the serialize/deserialize round-trip without explicit type hints on every cached class.

---

## Testing

**Both datasource URLs point to the same container in tests**
`BaseIntegrationTest` registers both `spring.datasource.write.url` and `spring.datasource.read.url` pointing at the same `PostgreSQLContainer`. There is no actual replica in tests — the routing logic is still exercised (reads go to "read", writes go to "write") but both resolve to the same database. This is intentional: replica lag is not testable in unit/integration tests and the routing mechanism itself is what's being verified.

**`timescale/timescaledb:latest-pg18` instead of plain postgres**
`BaseIntegrationTest` uses the TimescaleDB image as a compatible substitute for the `postgres` image. Plain PostgreSQL doesn't have the TimescaleDB extension, so Flyway migrations that create hypertables would fail. The image is fully compatible — no TimescaleDB-specific config is needed.

**`@ServiceConnection` is not used for the split datasource**
Spring Boot's `@ServiceConnection` auto-wires a single datasource. PayFlow has two (`write` and `read`) wired manually through `RoutingDataSource`, so `@ServiceConnection` cannot be used for PostgreSQL. `@DynamicPropertySource` is used instead to register both datasource URLs explicitly. `@ServiceConnection` is still used for Kafka and Redis in `TestcontainersConfiguration` since those have single connection points.

---

## Kafka

**`FixedBackOff(0, 0)` — zero retries at the Kafka level**
`KafkaConsumerConfig` registers a `DefaultErrorHandler` with no retries. Retrying at the Kafka listener level is redundant because the `processed_events` idempotency check already handles redelivery correctly. Adding retries here would just re-run a handler that already failed, usually for the same reason, before committing the offset.

**`consumer_group` column on `processed_events` composite PK**
The PK is `(event_id, consumer_group)`. Without the `consumer_group` discriminator, `AuditConsumer` marking an event as processed would block any future consumer from ever processing the same event, even if it has completely different responsibilities.

**`ack-mode: record`**
Offsets are committed after each individual record is processed, not after the whole batch. With `BATCH`, a consumer crash mid-batch redelivers every record in the batch — including ones already processed. With `RECORD`, only unprocessed records are redelivered. This pairs correctly with the `processed_events` idempotency check: duplicates are skipped, not reprocessed, but `RECORD` minimises unnecessary duplicate traffic.

---

## Observability

**`@PostConstruct` for gauge registration, no parameters**
`LedgerMetrics` and `OutboxMetrics` register Micrometer gauges in a `@PostConstruct` no-arg method. Spring lifecycle methods must be no-arg — passing `MeterRegistry` as a parameter would prevent Spring from invoking the method. The registry is injected as a field instead.

**ECS structured logging (`logging.structured.format.console: ecs`)**
Spring Boot 4's native structured logging writes every log line as a JSON object in Elastic Common Schema format. ECS is the standard schema used by Elasticsearch, OpenSearch, and most log aggregation pipelines — field names like `log.level`, `message`, `trace.id` are understood by tooling without custom mappings. The alternative is plain text with a pattern layout, which requires a parsing step before logs are queryable. Structured output is free here since Spring Boot 4 ships it natively.

---

## Infrastructure

**`@DynamicPropertySource` over `application-test.yml` for datasource config**
The Testcontainers PostgreSQL URL is only known at runtime after the container starts. A static `application-test.yml` cannot reference it. `@DynamicPropertySource` registers properties after the container is up, before the Spring context initializes.

**`DataSourceConfig` uses `@ConfigurationProperties` per datasource**
Each datasource (`write`, `read`) has its own `DataSourceProperties` bean bound to `spring.datasource.write.*` and `spring.datasource.read.*` respectively. This allows independent HikariCP pool configuration (pool size, connection timeout) per datasource without sharing settings.

**`ddl-auto: validate` everywhere**
Flyway owns the schema. `validate` makes Hibernate check that the entity mappings match the current schema at startup and fail fast if they don't — without ever modifying anything. `create` or `update` would let Hibernate silently diverge from the Flyway-managed schema.

**`EnumType.STRING` over `EnumType.ORDINAL`**
`ORDINAL` persists the enum's position in the declaration (0, 1, 2…). Reordering enum constants or inserting one changes all subsequent ordinals, silently corrupting existing rows. `STRING` persists the name — it's immune to reordering and self-documenting in the DB.

**`Instant` over `LocalDateTime` for timestamps**
`LocalDateTime` has no timezone information. At a DB boundary with a `TIMESTAMP WITH TIME ZONE` column, the JVM's default timezone determines the offset — a footgun across environments. `Instant` is always UTC, always unambiguous, and maps cleanly to `TIMESTAMPTZ`.

**Pool names set programmatically (`WritePool` / `ReadPool`)**
HikariCP pool name is set via `DataSourceProperties.initializeDataSourceBuilder().build()` followed by `((HikariDataSource) ds).setPoolName(...)`. The name cannot be set through `spring.datasource.write.hikari.pool-name` because `DataSourceProperties` doesn't expose the full Hikari config path when constructed manually. Named pools make the two datasources distinguishable in Hikari metrics and thread dumps.

---

## Domain

**Static factory methods over `@Builder` on aggregates**
`Wallet.open(...)`, `Transaction.create(...)`, etc. enforce invariants at construction — balance must be non-negative, currency must be present. A public `@Builder` lets callers construct an aggregate in any partial state and call `build()` before the invariants are set. Static factories keep the valid-state guarantee inside the aggregate root, not spread across callers.
