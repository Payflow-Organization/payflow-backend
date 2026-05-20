# Runbook

## Service Overview

| | |
|---|---|
| **Service** | PayFlow backend |
| **Stack** | Spring Boot 4, PostgreSQL 18, Kafka 3.9 (KRaft), Redis 8 |
| **Deployments** | Backend: Railway — Frontend: Vercel |
| **Metrics** | Prometheus + Micrometer (`/actuator/prometheus`) |
| **Health** | `/actuator/health` |

## Severity Levels

| Level | Criteria | Response |
|---|---|---|
| P1 | Money integrity at risk or authentication completely broken | Immediately |
| P2 | Degraded functionality — some requests failing or data delayed | Within 30 min |
| P3 | Operational concern — no user-facing impact | Within business hours |

---

## Outbox Relay Not Publishing

**Severity:** P2 — events are delayed but not lost; the outbox guarantees delivery once Kafka recovers

**Symptoms**
- `payflow.outbox.pending.size` gauge is rising and not draining between ticks
- `payflow.outbox.relay.failure` counter is incrementing
- No recent entries in `outbox_events` with `status = 'PROCESSED'`
- Downstream consumers (audit log) are falling behind

**Diagnosis**
```sql
-- Check for stuck or failing events
SELECT id, event_type, status, retry_count, last_error, created_at
FROM outbox_events
WHERE status IN ('PENDING', 'FAILED')
LIMIT 20;
```
- If `retry_count` is maxed and `status = 'FAILED'` — the relay gave up. Check `last_error` for the root cause.
- If `status = 'PENDING'` with low `retry_count` — the relay is running but Kafka is rejecting publishes. Check Kafka broker health.
- If no rows are PENDING at all but consumers are lagging — the relay ran fine, the consumer is the problem (see Kafka Consumer Lag Spike).

**Fix**
- Kafka unreachable: restore Kafka connectivity. Pending events will be picked up on the next relay tick automatically — no manual intervention needed.
- Events stuck in `FAILED`: investigate `last_error`, fix the root cause, then reset manually:
```sql
UPDATE outbox_events SET status = 'PENDING', retry_count = 0 WHERE status = 'FAILED';
```
- Relay not scheduling at all: check that `@EnableScheduling` is active and the app started without errors.

**Post-incident**
- Verify all FAILED events were reprocessed and `payflow.outbox.pending.size` returned to zero.
- If Kafka was the root cause, check broker logs for the window and file a ticket to review Kafka capacity.

---

## Kafka Consumer Lag Spike

**Severity:** P2 — audit log is delayed or has gaps, money operations are unaffected

**Symptoms**
- Audit log entries are missing or delayed
- Consumer group offset is falling behind the latest offset on `transaction-events`
- `payflow.outbox.pending.size` is healthy (relay is publishing fine)

**Diagnosis**
- Check `AuditConsumer` logs for `DataIntegrityViolationException` — this is normal (duplicate skipped), not a failure.
- Check for `Failed to process record` from `DefaultErrorHandler` — this means the consumer threw an unhandled exception. The offset is still committed (by design) so the partition keeps moving, but the event was dropped.
- Check `processed_events` table for the missing `transactionId` — if the row exists, the event was processed but the audit log write failed separately.

```sql
-- Find events processed but with no matching audit log entry
SELECT pe.event_id FROM processed_events pe
LEFT JOIN audit_logs al ON al.entity_id = pe.event_id
WHERE pe.consumer_group = 'audit' AND al.entity_id IS NULL
ORDER BY pe.event_id DESC LIMIT 20;
```

**Fix**
- If the event was dropped due to a deserialization error or unexpected payload shape: fix the consumer, then manually re-publish the outbox event by resetting its status (see Outbox Relay runbook above).
- If the consumer is simply slow under load: with 3 partitions, a single consumer instance processes all three sequentially — add instances to parallelize.

**Post-incident**
- Confirm all missing audit log entries are accounted for — either reprocessed or explicitly explained as dropped.
- If events were dropped due to a consumer bug: add a test case covering the payload shape that caused the failure.

---

## Reconciliation Drift Detected

**Severity:** P1 — money integrity is at risk; treat as a write-path bug until proven otherwise

**Symptoms**
- `payflow.reconciliation.ledger.imbalance` counter incremented
- `payflow.reconciliation.wallet.discrepancy` counter incremented
- `[RECONCILIATION]` ERROR lines in logs at 02:00

**Diagnosis**
```sql
-- Global ledger check — should always be 0
SELECT SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS delta
FROM ledger_entries;

-- Find wallets where cached balance != ledger sum
SELECT w.id, w.current_balance AS cached,
       SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END) AS computed
FROM wallets w
JOIN ledger_entries le ON le.wallet_id = w.id
GROUP BY w.id, w.current_balance
HAVING w.current_balance != SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END);
```

**Fix**
- Do not auto-correct. The discrepancy is a symptom of a write-path bug.
- Identify the transaction that caused the drift using `created_at` on `ledger_entries` around the time the drift appeared.
- Fix the root cause in code first.
- Only after the bug is confirmed fixed, manually correct the cached balance in a reviewed migration:
```sql
UPDATE wallets SET current_balance = (
    SELECT SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END)
    FROM ledger_entries WHERE wallet_id = wallets.id
) WHERE id = '<affected_wallet_id>';
```

**Post-incident**
- Document the root cause and the affected wallet(s) in a postmortem.
- Verify the manual balance correction against the ledger sum before closing.
- Add a regression test for the write-path bug that caused the drift.

---

## Redis Unavailable

**Severity:** P2 — wallet reads fall back to DB transparently; logout token revocation is the only security-relevant gap

**Symptoms**
- `payflow.cache.error` counter incrementing (check `operation` tag: get/put/evict/clear)
- WARN logs from `LoggingCacheErrorHandler`: `Cache get error cache=wallets`
- Application is still serving requests (fallback to DB is automatic)

**Diagnosis**
- Check Redis connectivity from the app host.
- Check Redis memory — if `maxmemory` is hit with `noeviction` policy, writes will fail silently.
- Check whether `accessToken` denylist lookups are also failing — if so, users who logged out during the outage may have had their access tokens remain valid.

**Impact by operation**
| Operation | Redis down impact |
|---|---|
| Wallet reads | Falls back to DB transparently |
| Wallet writes | Evict fails silently — next read hits DB |
| Token denylist (logout) | Access token stays valid until expiry (max 15 min) |
| Refresh token | Not affected — stored in PostgreSQL |

**Fix**
- Restore Redis. Cache repopulates on next reads automatically.
- If denylist was affected: users who logged out during the window may have had a valid access token for up to 15 minutes after logout. No manual action required unless the outage was significantly longer.

**Post-incident**
- Confirm `payflow.cache.error` counter stopped incrementing after recovery.
- If denylist was affected for more than 15 minutes, log the window — affected users' logout was not honoured for that period.

---

## Read Replica Lag

**Severity:** P3 — users may see stale data on read endpoints; money operations on primary are unaffected

**Symptoms**
- Users report stale balances or missing recent transactions on read endpoints
- `@Transactional(readOnly = true)` queries returning outdated data

**Diagnosis**
```sql
-- Run on replica
SELECT now() - pg_last_xact_replay_timestamp() AS replication_lag;
```
- Under 1s: normal transient spike.
- Over 5s: replica is falling behind — check primary write load and replica I/O.
- Growing indefinitely: replication may be broken — check `pg_stat_replication` on primary.

**Fix**
- Transient lag: no action needed, reads catch up automatically.
- Replica consistently behind: reduce primary write load or scale replica I/O.
- For balance-critical reads that cannot tolerate lag: remove `readOnly = true` on the specific query handler — `RoutingDataSource` will route it to primary.
- Replication broken: depends on Railway hosting — check Railway dashboard for replica health and re-provision if necessary.

**Post-incident**
- If lag exceeded 30s regularly, consider adding a lag alert threshold to Prometheus.
- If balance-critical reads were routed to primary as a mitigation, revert once replica catches up.

---

## Refresh Token Table Growth

**Severity:** P3 — no user-facing impact; operational concern that degrades query performance over time

**Symptoms**
- `refresh_tokens` table growing unboundedly
- Rows with `revoked = true` or `expires_at < now()` accumulating

**Diagnosis**
```sql
SELECT
    COUNT(*) FILTER (WHERE revoked = true)                          AS revoked,
    COUNT(*) FILTER (WHERE expires_at < now() AND revoked = false)  AS expired,
    COUNT(*) FILTER (WHERE expires_at > now() AND revoked = false)  AS active
FROM refresh_tokens;
```

**Fix**
There is no scheduled cleanup job. Add a periodic purge — either a `@Scheduled` job in `RefreshTokenService` or a database-level cron via `pg_cron`:
```sql
DELETE FROM refresh_tokens
WHERE revoked = true OR expires_at < now() - INTERVAL '7 days';
```
The 7-day buffer retains recently expired tokens for audit purposes. Tokens that are both expired and older than 7 days have no operational value.

**Post-incident**
- Add a scheduled purge job to `RefreshTokenService` to prevent recurrence.
- Set a table size alert so this is caught before it impacts query performance.

---

## Vercel Proxy Not Forwarding to Railway

**Severity:** P1 — all API calls fail; authentication and every protected endpoint are broken

**Symptoms**
- Every API call returns a network error or hits the wrong host
- Browser DevTools Network tab shows requests going to the Railway domain (`*.railway.app`) instead of the Vercel domain
- `SameSite=Strict` blocks cookies — login sets the cookie but subsequent requests don't attach it
- Worked before a recent frontend deployment

**Diagnosis**
Open DevTools → Network → find any `/api/v1/...` request and inspect the request URL:
- Domain is `*.railway.app` — the browser is calling Railway directly; the proxy is not running
- Domain is `*.vercel.app` — proxy is working; look elsewhere

If the proxy is not running, check:
1. `NEXT_PUBLIC_BACKEND_URL` in Vercel environment variables — must be set to the Railway URL. If missing or wrong, the rewrite destination is undefined and Vercel drops the route.
2. The Axios client base URL — must be the relative `/api/v1`, never `NEXT_PUBLIC_BACKEND_URL` directly. If someone changed the client to use the env var as a base URL, the browser bypasses the proxy entirely.

**Fix**
- `NEXT_PUBLIC_BACKEND_URL` missing or wrong in Vercel: correct the env var and redeploy.
- Axios base URL pointing at Railway directly: revert the client to use relative `/api/v1` — the proxy is the only thing that should know the Railway URL.

**Post-incident**
- Confirm in DevTools that API requests show the Vercel domain after the fix.
- Confirm `accessToken` cookie is present and attached on subsequent requests.

---

## Authentication Cookies Not Being Sent

**Severity:** P1 — users cannot authenticate; all protected endpoints are inaccessible

**Symptoms**
- Users are logged in but API calls return 401
- Browser DevTools shows requests going out without the `accessToken` cookie
- Auth worked locally but broke after a frontend deployment

**Diagnosis**
- Check that Axios is still targeting a relative `/api/v1` URL, not `NEXT_PUBLIC_BACKEND_URL` directly. If the base URL was changed to point at Railway, the browser calls Railway directly and `SameSite=Strict` blocks the cookies — the Vercel proxy is load-bearing for this auth model.
- Check the browser Application tab — is the `accessToken` cookie present? If yes but not sent, `SameSite=Strict` is blocking a cross-site request. If absent, the login `Set-Cookie` header never arrived — check the login response headers.
- Check that `app.cors.allowed-origins` on Railway includes the current Vercel deployment URL.

**Fix**
- Axios base URL changed to Railway URL: revert to relative `/api/v1`.
- CORS origin missing: add the Vercel URL to `app.cors.allowed-origins` in Railway environment config.
- `Set-Cookie` missing: `Secure=true` requires HTTPS — confirm both Vercel and Railway are serving over HTTPS.

**Post-incident**
- Document what changed in the frontend deployment that broke the proxy routing.
- Add a smoke test to the deployment pipeline that verifies the `/api/v1/auth/login` endpoint returns a `Set-Cookie` header.

---

## Serialization Failure Rate Spike

**Severity:** P2 — some money operations failing under contention; no data corruption, clients can retry

**Symptoms**
- Elevated 409 / 500 error rate on deposit, withdrawal, or transfer endpoints
- `ObjectOptimisticLockingFailureException` or `PessimisticLockingFailureException` in logs after retry exhaustion
- Users reporting intermittent failures on money operations under load

**Diagnosis**
- Check if retries are exhausting consistently — if so, contention is higher than the retry policy absorbs.
- Check concurrent transaction volume on the affected wallet. A single wallet hit by many concurrent requests will always produce SSI conflicts — this is expected behavior, not a bug.
- Check `payflow.outbox.relay.latency` — a slow relay can indirectly slow the write path.

**Fix**
- Increase retry attempts or backoff window via `payflow.retry.max-attempts`, `payflow.retry.initial-interval-ms`, `payflow.retry.multiplier`, `payflow.retry.max-interval-ms`.
- If a single wallet is the hotspot (e.g. a system wallet receiving many concurrent credits): SERIALIZABLE isolation on a single row will always serialize — consider batching or queuing writes to that wallet.
- Serialization failures are not data corruption. No money is lost. Clients receive an error and can retry.

**Post-incident**
- Check if the spike was tied to a specific wallet (hotspot) or was broad across all wallets.
- If retry exhaustion was common, tune `payflow.retry.max-attempts` and backoff config and monitor for recurrence.
