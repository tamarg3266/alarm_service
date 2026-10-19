# V2 validation evidence

Date: 19 October 2026, Asia/Jerusalem.

## Final result

All seven requested gaps are implemented. The updated Docker application is running at http://localhost:8080. Existing PostgreSQL data was preserved. Flyway V2 was applied to the existing Docker database, and the archive volume is writable by the non-root application user (UID 10001).

## Automated regressions

- Java: Amazon Corretto 17.0.18; Maven 3.9.11; Spring Boot 3.5.16.
- H2 2.3.232: **21 tests, zero failures/errors/skips; BUILD SUCCESS**.
- PostgreSQL 18.3, isolated local test cluster: **21 tests, zero failures/errors/skips; BUILD SUCCESS**.
- Tests: 7 original integration tests, 8 hardening integration tests, 4 error-classification tests, 2 admission tests.
- Final logs: `gap-h2-final.log`, `gap-postgres-tests.log` (local ignored build evidence).
- Executable JAR: `target/alarm-service-1.0.0.jar`.

## Actual two-instance end-to-end run

The repeatable `tools/e2e.py` harness created a disposable database, launched two real JVMs on 18081/18082 and used real HTTP, raw sockets and SQL checks. It did not exercise fault injection against the user's app or database. Both JVMs and the separate PostgreSQL test server were stopped after verification.

**25 checks passed**, including a forced application kill during an insert and a real immediate PostgreSQL restart during a transaction. Full run evidence remains under `.tools/e2e-b3165af153`; the database name is recorded in its results JSON.

1. Fractional timestamp rejected over HTTP.
2. Invalid database text rejected before persistence.
3. HTTP raise commits history and active state.
4. Another instance recognizes replay.
5. Cross-instance concurrent duplicate accepted exactly once.
6. Blocked element does not block another element/instance.
7. Lock timeout is retryable 503.
8. Timed-out ingestion has no partial history.
9. Timed-out event can be retried.
10. Frozen history pagination works across instances.
11. Expired snapshot returns 410.
12. Lost response replay returns original receipt.
13. Process kill during insert rolls back history.
14. Other instance recovers killed request by original ID.
15. Receipt survives application restart.
16. Database restart during insert rolls back event.
17. Connection pool recovers after database restart.
18. Scheduled archive preserves full payloads before deleting live rows.
19. Archived ID replay remains idempotent across instances.
20. Archive replay cannot resurrect cleared state.
21. Idle upload rejected within deadline (5.03s).
22. Trickled upload rejected within deadline (6.02s).
23. HTTP burst is bounded with explicit 429 responses.
24. Lifetime storage capacity returns 507 without success.
25. Capacity rejection leaves no receipt.

The original end-to-end run exposed native PostgreSQL lock-timeout classification as an UncategorizedSQLException. It was fixed with nested SQLState classification and a regression test; the final full run passed. Legacy archive/replay races were addressed by sharing event locks and verifying receipt conflicts.

## Docker and browser verification

- Built the Docker image from final source successfully (`docker-build-final.log`).
- Recreated only the application container; existing `alarm-data` was preserved and `alarm-archives` added.
- Production container connected to PostgreSQL **17.11**, applied V2 on top of V1 and started on port 8080.
- Confirmed Flyway success versions 1 and 2 and one existing history row after deployment.
- Opened `/api/alarms/history` in headless Microsoft Edge: HTTP 200, original response fields plus snapshotId/expiresAt present.
- Sent fractional timestamp through browser fetch to the real Docker API: HTTP 400.
- Confirmed the application runs UID 10001 and `/archives` is writable.
- Existing user-facing application was not used for destructive crash, restart, quota or retention tests.

## Documentation and delivery

- The current HLD was recreated from the final source as a self-contained HTML document with embedded styles; the separate low-level document and stylesheet were removed.
- The recreated HLD was rendered at 1440px and 390px with no horizontal document overflow, visually inspected, and checked for external stylesheet/script dependencies. All local links and section anchors resolve. Backend tests were not repeated for this documentation-only change.
- Local document links and Maven XML validated.
- Submission ZIP regenerated with updated source, migrations, tests, operations guide and reusable end-to-end harness.

## Practical limits

- Full automated regression/fault testing used PostgreSQL 18.3; Docker migration/startup/browser smoke checks used 17.11. Do not interpret the smoke check as the full suite having run on PostgreSQL 17.
- Upload budgets include one final idle-read allowance; measured idle/trickle rejection was 5.03 / 6.02 seconds in the final run. These are measured observations, not network latency guarantees.
- Limits are per process; no sustained distributed capacity benchmark is claimed.
- Archive publication and database commit are separate resources. Crash recovery can leave duplicate exports, which archive consumers must deduplicate. Media loss, archive restore and backup recovery were not injected.
- Permanent identity storage is bounded by an explicit lifetime sequence ceiling, not silently expired receipts. Capacity increases and archive offloading require operators.
- Authentication, authorization, TLS ingress and optional notifications remain outside these seven fixes.
