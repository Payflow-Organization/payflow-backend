# ADR-018: Synchronous PDF/CSV Export over Async Job Queue

## Status
Accepted — Week 3   

## Context
PayFlow allows users to export their transaction history as PDF or CSV for a
user-supplied date range. The export reads from `ledger_entries` (via
`WalletStatementQueryHandler`), renders the rows into the target format, and
delivers the result to the client.

Two structural questions needed to be answered:

1. **Delivery model** — should the export be produced synchronously within the HTTP
   request, or submitted as a background job with the client polling or being notified
   when it is ready?
2. **PDF library** — no PDF generation exists in the Java standard library; a third-party
   library is required.

## Decision
Exports are generated synchronously within the HTTP response. The query handler streams
`TransactionView` rows from `ledger_entries` as a `Stream<TransactionView>` directly into
the format adapter (`PdfStatementAdapter` or `CsvStatementAdapter`), which writes to the
HTTP response `OutputStream` row by row. iText Core 9.6.0 is used for PDF generation;
Apache Commons CSV 1.14.1 for CSV.

## Alternatives Considered

**Async job queue (submit → poll or webhook)**
- Client submits an export request and receives a job ID; a background worker generates
  the file and stores it (S3 or local disk); client polls or receives a push notification
  when ready
- Necessary when exports are large enough that HTTP request timeout is a realistic risk,
  or when export is triggered on behalf of many users simultaneously (batch billing,
  regulatory reporting)
- Adds: job state table or queue service, worker pool, file storage, polling or
  notification endpoint, job TTL and cleanup — a significant operational surface
- At current scope (single user, bounded date range, one request at a time), there is no
  evidence that synchronous generation exceeds a request timeout
- YAGNI — introduce async job queue when the workload justifies it, not preemptively

**Streaming synchronous response (chosen)**
- Rows flow from `ledger_entries` → `Stream<TransactionView>` → format adapter →
  `OutputStream` — no intermediate collection; server-side memory use is bounded by the
  stream buffer regardless of how many rows are in the date range
- CSV adapter writes rows one at a time through an 8 KB `BufferedWriter`; PDF adapter
  collects into a `List` (iText requires random access to lay out pages) but the list
  is bounded by the user's own transaction history
- No job state, no storage, no polling endpoint — one HTTP request, one response
- If generation fails mid-stream the connection closes; the client retries the same
  request — no orphaned job records to clean up

**Apache PDFBox**
- Open-source, Apache-licensed, no AGPL considerations
- Lower-level API than iText — layout, tables, and pagination require manual coordinate
  arithmetic; significantly more code for a formatted statement
- iText's layout module handles table rendering, page breaks, and headers with a
  declarative API — the same statement output takes a fraction of the code

**iText Core (chosen)**
- Mature library with a high-level layout API (`Document`, `Table`, `Cell`, `Paragraph`)
  used widely in financial and enterprise Java applications
- iText 9.x is AGPL-licensed; acceptable for an educational/portfolio project with no
  commercial distribution requirement; revisit licensing if the project becomes a product
- `PdfDocument` + `PdfWriter` wrap the response `OutputStream` directly — no temp file,
  no copy step

## Rationale
The async job queue pattern is the right choice at scale — when exports run for minutes,
involve multiple tenants, or require scheduling. At PayFlow's current scope, it adds an
entire subsystem to solve a problem that does not yet exist. Synchronous streaming keeps
the export path as simple as a standard REST response while remaining correct for
single-user, bounded date-range workloads.

iText is the industry-standard Java PDF library for financial documents. The layout API
produces a well-formatted statement with far less code than any lower-level alternative.

## Consequences
- `itext-core 9.6.0` (BOM, AGPL) and `commons-csv 1.14.1` added to `pom.xml`
- `PdfExportPort` and `CsvExportPort` interfaces in `application/port/` isolate the
  controllers from the library choice — swapping iText requires changing only the adapter
- `CsvStatementAdapter` streams rows through `CSVPrinter` with an 8 KB buffered writer;
  memory use is O(buffer), not O(rows)
- `PdfStatementAdapter` collects rows into a `List` before rendering — iText's table
  layout requires all rows before it can paginate; memory use is O(rows in date range)
  for PDF exports specifically
- PDF/A archival format (ISO 19005) is not used — PDF/A requires embedded fonts and
  colour profiles and is mandated by regulatory archival requirements that do not apply
  at current scope; revisit if GDPR document retention or financial audit archival becomes
  a requirement
- If exports grow beyond what a single request can handle (very large wallets, multi-year
  ranges), the async job queue path is a clean addition: `WalletStatementQueryHandler`
  already encapsulates the query; a background worker would call it and write to S3
  instead of the HTTP response stream
