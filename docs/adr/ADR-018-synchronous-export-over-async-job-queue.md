# ADR-018: Synchronous PDF/CSV Export over Async Job Queue

## Status
Accepted — Week 3

## Context
PayFlow exposes export endpoints for transaction history — users can
download their ledger as PDF or CSV. Two approaches exist: blocking the
HTTP response while the file is generated, or queuing the export as a
background job and notifying the user when it's ready.

Export requests query `ledger_entries` for a wallet's transaction history
and either format it as CSV via Apache Commons CSV or render it as PDF
via iText 9.

## Decision
Generate exports synchronously — the HTTP response blocks until the file
is ready and returns it directly as a download. No job queue, no
background worker, no notification mechanism.

## Alternatives Considered

**Async job queue**
- Export request enqueued → background worker generates file → user
notified (email, polling, webhook). Scales to large exports without
blocking HTTP threads and handles spikes gracefully.

The cost: significant infrastructure — a job queue (Kafka or dedicated),
a worker pool, file storage (S3 or similar), and a notification mechanism.
None of this exists in PayFlow and adding it purely for export would be
premature complexity.

## Rationale
PayFlow's export scope is a single wallet's transaction history — bounded
in size and fast to generate. Blocking the HTTP thread for a few hundred
milliseconds is acceptable. The async path adds infrastructure complexity
that is only justified when exports are large enough to risk HTTP timeouts
or frequent enough to create thread pool pressure. Neither condition
applies at current scale.

PDF/A archival format is not implemented — revisit if regulatory
compliance (PSD2, GDPR audit trails) becomes a requirement.

## Consequences
- No additional infrastructure required — export is a pure query-side
  concern handled within the existing request lifecycle
- Export endpoints will be slow under concurrent load — HTTP threads
  block for the duration of file generation
- Large export requests (full account history) may approach HTTP timeout
  thresholds at scale

## Future Considerations
If exports grow in size or frequency, the natural migration path is
Kafka-backed async generation with S3 storage and a polling or webhook
notification. The export logic (query + format) is already isolated
(moving it to a background worker is additive, not a rewrite)
