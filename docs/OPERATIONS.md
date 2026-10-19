# Capacity, retention and recovery

## Upgrading from V1

Back up the database first, then rebuild the application image (`docker compose up --build -d`). Flyway V2 adds accepted timestamps, exact-key locks, duplicate receipts and query snapshots without dropping V1 alarm data. Existing rows receive the migration time as their acceptance time, so they are not immediately archived. Legacy duplicate receipts are backfilled on replay or archival.

**Stop old application instances before upgrading.** V1 uses a global lock while V2 uses per-key locks; mixing writer versions would violate the shared locking protocol. Multiple V2 instances may safely share one database. Use identical capacity and archival configuration across instances, and mount the same archive storage if they share maintenance duties.

## Stable pagination

GET `/api/alarms/history?size=20` creates a frozen snapshot of matching records. The response includes `snapshotId` and `expiresAt` in addition to the original fields. Request page 1 using `/api/alarms/history?size=20&page=1&snapshotId=TOKEN`. Use the same endpoint and element filter. A token can be read by another V2 instance using the same database.

The frozen payload makes count and pages consistent even if alarms change or live history is archived. An active-alarm snapshot describes its creation time; create a new snapshot to refresh current state. A page greater than zero without a token returns 400. An expired/unknown token returns 410; restart from page zero. Query mismatches return 400. Snapshot tokens are random UUID capabilities, not authentication.

Defaults: 120-second TTL, at most 32 snapshots, 10,000 matching records and 4 MiB encoded payload per snapshot. Full snapshot capacity returns 429; too many records or bytes returns 413. Filter by element, wait for expiry, or deliberately raise deployment limits after sizing memory/storage. Cleanup runs during snapshot creation and maintenance. A snapshot is materialized by one committed database SELECT; it is not a live offset query or an ID high-water approximation.

## Retention and event identity

Every minute the maintenance job exports up to 500 inactive history records accepted more than 30 days ago. It never removes an event referenced by active state. Retention uses server `accepted_at`, not the producer timestamp. Files are GZIP-compressed JSON Lines containing the full public event payload and original history ID. Their location in Compose is the persistent `alarm-archives` volume at `/archives`; local default is `./archives`.

The job writes, closes, fsyncs and atomically renames an archive before deleting live rows in its database transaction. Duplicate receipts are created before deletion, under the same event lock as ingestion. A receipt preserves event ID, original history ID and canonical SHA-256 payload fingerprint. Identical replay returns 200 after archival; changed content returns 409, and neither can reactivate cleared state.

Database and filesystem are not one atomic resource: a crash after file publication but before database commit can leave an extra export. Consumers must deduplicate archive records by event ID. The protocol avoids deleting the only payload copy; it is not an exactly-once archive. Back up both volumes. Atomic filesystem rename and file flush are required; storage/media durability and directory-entry persistence still depend on the filesystem and host.

The archive directory has a 1 GiB compressed-file budget and 10,000-file cap. Incomplete crash files count toward the budget. If storage is unavailable or full, retention logs an operator-action error and keeps database history; it never silently drops rows. Offload completed archives to verified backup storage before removing local copies. Do not remove the only copy. No unauthenticated HTTP archive download endpoint is exposed; operators access the volume directly.

Live history, receipts and exact-key lock cardinality are additionally bounded by a **lifetime history-ID ceiling**, default 10,000,000. New events allocated above that ID return 507 and roll back history, active state and receipt changes. Database sequences allocate atomically without a global transaction lock. Rollbacks consume sequence values, so this is a conservative admission bound, not a precise accepted-event counter. Duplicate replays remain available when capacity is exhausted. Sequence allocation continues on failed admissions; never reset/reuse IDs in a live database.

This explicit ceiling resolves the conflict between unlimited global ID deduplication and finite storage. Increasing it is an operator capacity decision; archival does not free IDs. Override MAX_HISTORY_ID, HISTORY_RETENTION_DAYS and MAX_ARCHIVE_BYTES in the Compose environment. Budget database indexes, active state and lock rows when selecting the ceiling. Export/backup capacity and disk monitoring remain operator responsibilities; a file-size budget is not a filesystem quota.

## Ingestion concurrency and errors

Transactions take `event:EVENT_ID` then `element:ELEMENT_ID` row locks. Rows are created with parameterized INSERT ON CONFLICT DO NOTHING and locked with SELECT FOR UPDATE. There are no hash collisions or in-memory-only locks. Different elements with different event IDs proceed independently, subject to the bounded connection pool. Events for one element remain serialized. Duplicate IDs across elements are serialized before examining state. Replays need only the event lock. Retention takes event locks while transferring legacy records so receipt creation and deletion cannot race a replay.

The old `ingestion_lock` row now coordinates only snapshot quota and maintenance, not ingestion. Never write alarm state directly outside this protocol. PostgreSQL READ COMMITTED is required. Global generated IDs express allocation order; after parallelization they do not imply global commit order across elements. Within an element, later accepted unique events win, regardless of producer timestamp.

400: invalid client data, including fractional/exponent-float timestamps and NUL text. 408: upload idle timeout or total deadline. 409: business/constraint conflict. 413: body/snapshot size. 429: admission or snapshot capacity. 503 with Retry-After: transient connectivity, lock timeout, deadlock, cancellation or resource failure. 500: permanent/unrecognized storage failure requiring an operator. 507: lifetime admission capacity. Reuse the same ID for uncertain or retryable ingestion outcomes; do not continuously retry permanent failures.

## HTTP resource limits

At most 32 admitted requests per process; 30 requests/second per socket peer with a burst of 120; at most 1,024 client buckets, expired after two inactive minutes. No X-Forwarded-For header is trusted. Rejection returns 429 with Retry-After: 1. Limits are per replica; an Internet-facing deployment still needs authenticated ingress and a distributed policy.

POST input is limited to 16 KiB. The filter checks a five-second wall-clock upload budget before and after each read, and Tomcat enforces a five-second blocking/idle upload timeout. A final blocked read can extend the wall-clock budget by at most one idle timeout (approximately ten seconds maximum, excluding scheduling/network delays). Both stalled and continuously trickled bodies release the request slot. The API uses synchronous servlet input; async request streams are unsupported. Threads, active sockets and database connections also need deployment-level sizing; these controls do not make the service resistant to every denial-of-service attack.

## Repeating fault-injection verification

`tools/e2e.py` uses only Python's standard library, a built JAR, Java 17 and PostgreSQL client tools. Set JAVA_BIN and PG_BIN when not on PATH. Set PGHOST, PGPORT, PGUSER and PGPASSWORD for a disposable server with database-creation privileges. It creates its own `alarm_e2e_*` database and uses ports 18081/18082, refusing occupied ports.

```bash
mvn clean verify
python tools/e2e.py
```

The script starts two JVMs, verifies actual HTTP requests, checks committed SQL data, kills an application during an insert, discards a committed response, exercises slow sockets and overload, and checks scheduled archives. Processes are always stopped. It retains its uniquely named database, archives and logs under `.tools/e2e-RUN_ID` for inspection.

Set E2E_PGDATA only to a **dedicated test cluster inside this workspace's `.tools` directory** to enable real immediate PostgreSQL restart during an insert. The harness verifies the actual connected data-directory path matches before restarting. This option interrupts the entire selected cluster; never put user data in that test cluster. Without it, the script explicitly reports the restart check as skipped. Never point Maven integration tests at valuable data either: they delete alarm tables and reset test identities.
