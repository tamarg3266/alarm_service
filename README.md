# Telecom Alarm Service

Java 17 / Spring Boot 3.5.16 / Spring MVC / Spring Data JPA / Flyway / PostgreSQL backend for the supplied assignment. Core requirements and optional paginated history are implemented. Optional HTTP notifications are intentionally out of scope.

## Start

Install Docker with Compose, or Java 17+ and Maven 3.6.3+ for local development.

PowerShell:

```powershell
$env:DB_PASSWORD = 'choose-a-long-local-development-password'
docker compose up --build -d
```

Bash:

```bash
export DB_PASSWORD='choose-a-long-local-development-password'
docker compose up --build -d
```

Service: http://localhost:8080. Only the application port is published, on loopback. PostgreSQL is accessible to the app through Docker's internal network at `db:5432`; it does not reserve host port 5432. Data survives container recreation in the `alarm-data` volume. `docker compose down` preserves data; do not delete the volume unless intentionally resetting the database.

For local Java development, use a separately accessible PostgreSQL database (Compose does not publish its database port), set `DB_URL`, `DB_USER` and `DB_PASSWORD`, then run `mvn spring-boot:run`. Optional settings: `DB_URL`, `DB_USER`, `SERVER_ADDRESS`. No password is embedded in source. Flyway installs the schema; Hibernate validates it. A missing password or unavailable database fails startup. The container runs Java as an unprivileged numeric user.

## Requests

Use `curl.exe` on Windows where `curl` is a PowerShell alias. Bash examples:

```bash
curl -i http://localhost:8080/api/alarms \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"EVT-10001","elementId":"BTS-102","alarmCode":"CELL_DOWN","severity":"CRITICAL","status":"RAISED","timestamp":1791800000000,"description":"Cell is not responding"}'
curl http://localhost:8080/api/alarms/active
curl http://localhost:8080/api/alarms/elements/BTS-102
curl http://localhost:8080/api/alarms/statistics
curl 'http://localhost:8080/api/alarms/history?elementId=BTS-102&page=0&size=20'
curl -i http://localhost:8080/api/alarms \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"EVT-10002","elementId":"BTS-102","alarmCode":"CELL_DOWN","severity":"INFO","status":"CLEARED","timestamp":1791800001000,"description":"Recovered"}'
```

New events return 201 with `eventId`, `historyId`, `duplicate:false`. Exact replays return 200 with the original history ID and `duplicate:true`, even after a later clear. Reusing an ID with different content returns 409. Clearing an unknown/inactive alarm returns 409 and writes nothing. Malformed input returns 400; oversized POST bodies return 413; transient database failures return 503 with `Retry-After: 1`; permanent storage failures return 500 without retry advice, integrity conflicts return 409, admission returns 429, upload timeouts return 408, and lifetime capacity returns 507. Retrying must use the same event ID and content.

List endpoints return `{content, page, size, totalElements, snapshotId, expiresAt}`. Defaults: page 0, size 100; size 1..500. The first request freezes matching records in a short-lived persistent snapshot. Pass its `snapshotId` on every later page with the same endpoint/filter. Page >0 without a token returns 400; expired tokens return 410. This keeps content and counts stable across inserts, clears, archival and different application instances. To refresh active state, start a new snapshot.

```bash
# Copy snapshotId from this response:
curl 'http://localhost:8080/api/alarms/history?size=20'
# Replace TOKEN with it:
curl 'http://localhost:8080/api/alarms/history?size=20&page=1&snapshotId=TOKEN'
```

Snapshots expire after 120 seconds and are capped at 32 snapshots, 10,000 matching events and 4 MiB per snapshot. Capacity errors are explicit (429/413); filter by element or size deployment limits deliberately. Statistics are live database counts and do not use snapshots.

## Architecture and SOLID

The root package contains only the Spring Boot entry point so component, entity, and repository scanning cover all child packages.

```text
com.septier.alarms
  AlarmApplication
  api                         HTTP routing and request/response adaptation
    dto                       Explicit JSON response contract
    error                     HTTP problem responses
    filter                    Bounded request bodies and response headers
  application                 Transactional alarm use cases
    command                   Validated ingestion input
  domain
    model                     Alarm identity, history, active state, enums
    exception                 Business conflict
  infrastructure
    persistence               Spring Data repositories and database lock
```

This is a pragmatic layered architecture. API uses the application service; the service coordinates domain objects through injected repository interfaces. Domain code never imports application or API classes. Persistence uses the domain model. JPA annotations remain on domain entities and Spring Data paging remains in application signatures; this is intentionally not a framework-independent hexagonal implementation. Response DTOs prevent accidental persistence field exposure. Separate query repositories and lock repository keep responsibilities focused. Constructor injection provides explicit dependencies; avoid unnecessary interfaces with one implementation.

## Consistency and concurrency

Every ingestion transaction acquires exact database locks in order: `event:EVENT_ID`, then `element:ELEMENT_ID`. A parameterized INSERT ON CONFLICT DO NOTHING creates each lock row safely; SELECT FOR UPDATE holds it until commit. Independent elements with different event IDs proceed concurrently. Global event-ID conflicts and events on the same element serialize correctly across replicas. The original singleton lock now coordinates only snapshot capacity and retention, never ingestion.

History, duplicate receipt and active state commit or roll back together. Receipts retain the original history ID and a canonical SHA-256 payload fingerprint even after history archival. Legacy V1 receipts are backfilled on replay or archival. Identical replay is checked before state transitions and never resurrects an old alarm.

Acceptance order is successful processing order per element, not network arrival or producer timestamp. Older/equal timestamps take effect when accepted later. History IDs are allocated sequences with possible gaps; they no longer express global commit order across elements. Two concurrent clears accept one; raise versus clear follows element lock order. PostgreSQL uses READ COMMITTED, 5-second lock timeout, 10-second statement/ingestion timeout and 3-second pool acquisition timeout. No HTTP notification occurs under a transaction.

Stop old V1 writer instances before upgrading to V2; their locking protocols cannot safely be mixed. Multiple V2 replicas share exact-key locks and database snapshots. Do not modify state outside this protocol or reset live identity sequences.

## Retention and capacity

Every minute, up to 500 inactive history records accepted more than 30 days ago are archived as compressed JSON Lines before removal from the live database. Active references are never pruned. Full payloads persist in the `alarm-archives` Docker volume (`./archives` locally); duplicate receipts remain in PostgreSQL. The history API lists retained database history; older payloads are available to operators in archives. Snapshot payloads remain stable after archival.

Archive-before-delete prevents losing the only copy; a crash between file publication and database commit can leave duplicate exports, so archive consumers deduplicate by event ID. Archive failures retain live rows and log an operator-action error. Default archive budget: 1 GiB and 10,000 files. Back up and offload archives safely; no automatic deletion of the only archive copy occurs.

Permanent global deduplication needs permanent ID storage. To bound it, new history IDs above MAX_HISTORY_ID (default 10,000,000) are rejected atomically with 507. Rollbacks consume sequence IDs too, so this is a conservative lifetime capacity ceiling. Replays still succeed at capacity. Configure MAX_HISTORY_ID, HISTORY_RETENTION_DAYS and MAX_ARCHIVE_BYTES in the Compose environment after sizing storage; archival does not reset identity capacity. Both `alarm-data` and `alarm-archives` volumes must be backed up.

See [operations and recovery](docs/OPERATIONS.md) for precise failure and storage semantics.

## Validation and security

Identifiers are case-sensitive, untrimmed ASCII `[A-Za-z0-9_.:-]`, with event/element IDs limited to 128 characters and alarm codes to 64. Severity and status are exact uppercase enum strings. Description is optional, up to 2048 characters; omitted and JSON null are equivalent, but empty string is different. Unknown JSON fields, duplicate keys, trailing JSON, scalar coercion, numeric enums, fractional/exponent-float timestamps, negative timestamps, NUL descriptions and invalid pagination are rejected. POST bodies are bounded to 16 KiB before deserialization, including chunked requests. SQL access is parameterized by JPA. Problem responses avoid SQL, credentials, and stack traces. Application database-failure logs contain exception class only, not submitted payloads.

This assignment service has no authentication or authorization and is intended for synthetic data on a trusted local network. Before exposing it, place it behind authenticated TLS ingress with request rate and read time limits, and restrict database access. The app now has bounded admission (32 simultaneous requests), per-peer token buckets (30 requests/second, burst 120, at most 1,024 peer buckets), and five-second wall-clock upload checks plus Tomcat idle/read timeouts. See OPERATIONS.md for the final-read timeout allowance and replica scope. The Compose database user owns the schema for easy migrations; production should separate migration and restricted runtime credentials. Keep dependencies and container images patched and run dependency scanning in your deployment pipeline. There is no outbound HTTP client or SSRF surface.

## Tests and build

```bash
mvn clean verify
java -jar target/alarm-service-1.0.0.jar
```

The integration suite exercises real Spring MVC validation, Flyway, Hibernate, transactions, queries, and concurrent ingestion against H2 in PostgreSQL compatibility mode. Tests cover lifecycle, replay after clear/archival, legacy receipt migration, rejected fractional/NUL input, stable snapshot pages and expiry/capacity, archive filesystem failure, lifetime quota, rate/concurrency admission, PostgreSQL error classification and competing raise/clear transactions. `tools/e2e.py` additionally runs two actual JVMs against PostgreSQL and checks slow sockets, lock isolation, lost responses, process crashes, scheduled archives, overload and optional isolated database restart. See docs/VALIDATION.md for executed evidence and docs/OPERATIONS.md to repeat it.

To run the same suite against **a disposable PostgreSQL database**, set `TEST_DB_URL`, `TEST_DB_USER`, and `TEST_DB_PASSWORD`, then run `mvn clean verify`. Tests delete all alarm state and history before each test; never use a database containing wanted data. Use a separately accessible disposable test database; the Compose database is intentionally not published to the host. The test profile replaces PostgreSQL session timeouts with `SELECT 1` to remain portable; production timeout configuration requires a separate deployment smoke check.

H2 does not prove PostgreSQL-specific locking or deployment behavior. Docker Compose must be checked with a running Docker daemon. See `docs/HLD.html` and `docs/LLD.html` for detailed design and operational tradeoffs.

## Limitations and future extensions

Optional notifications, automatic ingestion retries, authentication, audit actor identity, production monitoring and sustained distributed load benchmarks remain outside scope. Retention, hard capacity admission and a two-instance fault harness are implemented. State modifications outside the service can violate its lock protocol. Request limits are per replica; public deployment requires authenticated ingress and shared rate policy.

For notifications, write an outbox row in the ingestion transaction, then use workers partitioned by element with a durable sequence and lease ownership. Bound outstanding work and reject ingestion atomically if admission capacity is exhausted. Use connect/read deadlines, documented retry/idempotency rules, and multiple partitions for cross-element concurrency; a multi-instance design must ensure only one owner processes a given element at a time. This is a proposed extension, not implemented behavior.

Substantial implementation and documentation were generated with OpenAI Codex and verified with the automated suite. No external solution source was copied. Official Spring Boot system requirements: https://docs.spring.io/spring-boot/3.5/system-requirements.html.

Detailed [HLD](docs/HLD.html), [LLD](docs/LLD.html), [operations](docs/OPERATIONS.md) and [validation evidence](docs/VALIDATION.md).
