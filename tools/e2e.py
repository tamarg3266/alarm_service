"""Destructive tests on a newly created alarm_e2e_* database only; never uses the running app.

Requires Java 17+, PostgreSQL client tools, a packaged JAR and a disposable PostgreSQL server.
Set JAVA_BIN, PG_BIN, PGHOST, PGPORT, PGUSER and optionally PGPASSWORD.
Optional E2E_PGDATA enables a real immediate restart; it MUST be inside this workspace's .tools.
The created database is retained for inspection. Application processes are always stopped.
"""
import concurrent.futures as futures
import gzip
import json
import os
from pathlib import Path
import re
import select
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
RUN = uuid.uuid4().hex[:10]
DB = "alarm_e2e_" + RUN
HOST, PORT, USER = os.getenv("PGHOST", "127.0.0.1"), os.getenv("PGPORT", "55432"), os.getenv("PGUSER", "alarmtest")
PG_BIN = Path(os.environ["PG_BIN"]) if "PG_BIN" in os.environ else None
PSQL = str(PG_BIN / ("psql.exe" if os.name == "nt" else "psql")) if PG_BIN else shutil.which("psql")
JAVA = os.getenv("JAVA_BIN", shutil.which("java"))
WORK = ROOT / ".tools" / ("e2e-" + RUN)
WORK.mkdir(parents=True)
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
PROCESSES, LOGS, RESULTS = [], [], []


def check(condition, label):
    if not condition:
        raise AssertionError(label)
    RESULTS.append(label)
    print("PASS " + label, flush=True)


def sql(statement, database=DB, extra_env=None):
    env = dict(os.environ)
    env.update(extra_env or {})
    return subprocess.check_output([PSQL, "-X", "-v", "ON_ERROR_STOP=1", "-h", HOST, "-p", PORT,
        "-U", USER, "-d", database, "-Atc", statement], text=True, env=env, creationflags=FLAGS).strip()


def wait_for(predicate, timeout=30):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            if predicate():
                return
        except (OSError, urllib.error.URLError, subprocess.CalledProcessError):
            pass
        time.sleep(.15)
    raise TimeoutError("Condition was not reached")


def request(port, path, event=None):
    data = None if event is None else json.dumps(event).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/api/alarms" + path, data=data,
        headers={"Content-Type": "application/json"})
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.loads(response.read())


def event(event_id, element="E2E-1", status="RAISED"):
    return dict(eventId=event_id, elementId=element, alarmCode="CELL_DOWN", severity="CRITICAL",
        status=status, timestamp=1791800000000, description="Synthetic E2E event")


def start_app(port, name, retention=False):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))  # refuse to interfere with an existing listener
    env = dict(os.environ, DB_URL=f"jdbc:postgresql://{HOST}:{PORT}/{DB}?ApplicationName={name}",
        DB_USER=USER, DB_PASSWORD=os.getenv("PGPASSWORD", "test"))
    log = open(WORK / (name + ".log"), "w", encoding="utf-8")
    LOGS.append(log)
    args = [JAVA, "-jar", str(ROOT / "target/alarm-service-1.0.0.jar"), f"--server.port={port}",
        "--server.address=127.0.0.1", "--alarm.max-history-id=5000",
        "--alarm.retention.enabled=" + str(retention).lower(), "--alarm.retention.days=1",
        "--alarm.retention.interval-ms=1000", "--alarm.retention.archive-directory=" + str(WORK / "archives")]
    process = subprocess.Popen(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
    PROCESSES.append(process)
    wait_for(lambda: process.poll() is None and request(port, "/statistics")[0] == 200, 60)
    print(f"READY {name} on {port}", flush=True)
    return process


def pause_insert(event_id, seconds=4):
    assert re.fullmatch(r"[a-z-]+", event_id)
    sql("CREATE OR REPLACE FUNCTION e2e_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
        f"IF NEW.event_id='{event_id}' THEN PERFORM pg_sleep({seconds}); END IF; RETURN NEW; END $$; "
        "CREATE TRIGGER e2e_pause AFTER INSERT ON alarm_history FOR EACH ROW EXECUTE FUNCTION e2e_pause();")


def waiting(name):
    return sql(f"SELECT count(*) FROM pg_stat_activity WHERE application_name='{name}' AND wait_event='PgSleep'") != "0"


def raw_upload(trickle=False):
    with socket.create_connection(("127.0.0.1", 18081), timeout=12) as connection:
        connection.sendall(b"POST /api/alarms HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{")
        began = time.monotonic()
        if trickle:
            for _ in range(10):
                time.sleep(.75)
                if select.select([connection], [], [], 0)[0]:
                    break
                connection.sendall(b" ")
        response = connection.recv(8192)
        elapsed = time.monotonic() - began
        check(b" 408 " in response and elapsed < 11, ("Trickled" if trickle else "Idle") + f" upload rejected within deadline ({elapsed:.2f}s)")


def main():
    if not PSQL or not JAVA:
        raise RuntimeError("Set PG_BIN and JAVA_BIN to installed PostgreSQL tools and Java 17+")
    sql('CREATE DATABASE "' + DB + '"', database="postgres")
    a = start_app(18081, "alarm-e2e-a", retention=True)
    start_app(18082, "alarm-e2e-b")
    print("DATABASE " + DB, flush=True)
    fractional = event("fractional"); fractional["timestamp"] = 1.9
    check(request(18081, "", fractional)[0] == 400, "Fractional timestamp rejected over HTTP")
    nul = event("nul"); nul["description"] = "bad\0text"
    check(request(18081, "", nul)[0] == 400, "Invalid database text rejected before persistence")
    check(request(18081, "", event("first"))[0] == 201, "HTTP raise commits history and active state")
    check(request(18082, "", event("first"))[0] == 200, "Another instance recognizes replay")
    with futures.ThreadPoolExecutor(max_workers=12) as pool:
        statuses = list(pool.map(lambda i: request(18081+i%2, "", event("shared", "SHARED"))[0], range(12)))
    check(statuses.count(201) == 1 and statuses.count(200) == 11, "Cross-instance concurrent duplicate accepted exactly once")

    sql("INSERT INTO ingestion_key_lock(lock_key) VALUES ('element:BLOCKED') ON CONFLICT DO NOTHING")
    blocker_log = open(WORK / "blocker.log", "w"); LOGS.append(blocker_log)
    blocker = subprocess.Popen([PSQL, "-X", "-h", HOST, "-p", PORT, "-U", USER, "-d", DB, "-c",
        "BEGIN; SELECT lock_key FROM ingestion_key_lock WHERE lock_key='element:BLOCKED' FOR UPDATE; SELECT pg_sleep(12); COMMIT;"],
        env=dict(os.environ, PGAPPNAME="alarm-e2e-blocker"), stdout=blocker_log, stderr=subprocess.STDOUT, creationflags=FLAGS)
    PROCESSES.append(blocker)
    wait_for(lambda: waiting("alarm-e2e-blocker"))
    with futures.ThreadPoolExecutor() as pool:
        blocked = pool.submit(request, 18081, "", event("blocked", "BLOCKED"))
        time.sleep(.3)
        began = time.monotonic(); other = request(18082, "", event("independent", "INDEPENDENT"))
        check(other[0] == 201 and time.monotonic()-began < 2, "Blocked element does not block another element/instance")
        check(blocked.result()[0] == 503, "Lock timeout is retryable 503")
    sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='alarm-e2e-blocker'")
    check(sql("SELECT count(*) FROM alarm_history WHERE event_id='blocked'") == "0", "Timed-out ingestion has no partial history")
    check(request(18082, "", event("blocked", "BLOCKED"))[0] == 201, "Timed-out event can be retried")

    status, snapshot = request(18081, "/history?size=1")
    before = snapshot["totalElements"]; token = snapshot["snapshotId"]
    request(18082, "", event("after-snapshot", "SNAPSHOT"))
    status, later = request(18082, f"/history?page=1&size=1&snapshotId={token}")
    check(status == 200 and later["totalElements"] == before and later["content"][0]["eventId"] != "after-snapshot",
        "Frozen history pagination works across instances")
    sql("UPDATE query_snapshot SET expires_at=now()-interval '1 second' WHERE token='"+token+"'")
    check(request(18081, "/history?snapshotId="+token)[0] == 410, "Expired snapshot returns 410")

    # Send a request and deliberately discard the response after observing its commit.
    payload = json.dumps(event("lost-response", "LOST")).encode()
    with socket.create_connection(("127.0.0.1", 18081), timeout=10) as connection:
        connection.sendall(b"POST /api/alarms HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: "
            + str(len(payload)).encode() + b"\r\nConnection: close\r\n\r\n" + payload)
        wait_for(lambda: sql("SELECT count(*) FROM event_receipt WHERE event_id='lost-response'") == "1")
    check(request(18082, "", event("lost-response", "LOST"))[0] == 200, "Lost response replay returns original receipt")

    pause_insert("crash-inflight")
    with futures.ThreadPoolExecutor() as pool:
        pending = pool.submit(request, 18081, "", event("crash-inflight", "CRASH"))
        wait_for(lambda: waiting("alarm-e2e-a")); a.kill(); a.wait(timeout=10)
        try: pending.result(timeout=10)
        except (OSError, urllib.error.URLError): pass
    time.sleep(4.5)
    sql("DROP TRIGGER e2e_pause ON alarm_history")
    check(sql("SELECT count(*) FROM alarm_history WHERE event_id='crash-inflight'") == "0", "Process kill during insert rolls back history")
    check(request(18082, "", event("crash-inflight", "CRASH"))[0] == 201, "Other instance recovers killed request by original ID")
    a = start_app(18081, "alarm-e2e-a", retention=True)
    check(request(18081, "", event("first"))[0] == 200, "Receipt survives application restart")

    pgdata = os.getenv("E2E_PGDATA")
    if pgdata:
        data = Path(pgdata).resolve(); safe = (ROOT / ".tools").resolve()
        if not data.is_relative_to(safe) or not (data / "PG_VERSION").exists() or HOST != "127.0.0.1":
            raise RuntimeError("Database restart allowed only for a local cluster inside workspace .tools")
        # Check the configured cluster really is the one serving our disposable database.
        actual = Path(sql("SHOW data_directory")).resolve()
        if actual != data: raise RuntimeError("E2E_PGDATA does not match connected server")
        pause_insert("db-restart", 8)
        with futures.ThreadPoolExecutor() as pool:
            pending = pool.submit(request, 18081, "", event("db-restart", "RESTART"))
            wait_for(lambda: waiting("alarm-e2e-a"))
            ctl = str(PG_BIN / ("pg_ctl.exe" if os.name == "nt" else "pg_ctl"))
            subprocess.run([ctl, "-D", str(data), "-m", "immediate", "-w", "restart"], check=True,
                stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, creationflags=FLAGS)
            try: pending.result(timeout=15)
            except (OSError, urllib.error.URLError): pass
        wait_for(lambda: request(18082, "/statistics")[0] == 200)
        sql("DROP TRIGGER e2e_pause ON alarm_history")
        check(sql("SELECT count(*) FROM alarm_history WHERE event_id='db-restart'") == "0", "Database restart during insert rolls back event")
        check(request(18082, "", event("db-restart", "RESTART"))[0] == 201, "Connection pool recovers after database restart")
    else:
        print("SKIP database restart: E2E_PGDATA not set", flush=True)

    request(18082, "", event("archive-raise", "ARCHIVE"))
    request(18082, "", event("archive-clear", "ARCHIVE", "CLEARED"))
    sql("UPDATE alarm_history SET accepted_at=now()-interval '40 days' WHERE element_id='ARCHIVE'")
    wait_for(lambda: sql("SELECT count(*) FROM alarm_history WHERE element_id='ARCHIVE'") == "0")
    content = "".join(gzip.open(p, "rt", encoding="utf-8").read() for p in (WORK/"archives").glob("*.gz"))
    check("archive-raise" in content and "archive-clear" in content, "Scheduled archive preserves full payloads before deleting live rows")
    check(request(18082, "", event("archive-raise", "ARCHIVE"))[0] == 200,
        "Archived ID replay remains idempotent across instances")
    check(sql("SELECT count(*) FROM active_alarm WHERE element_id='ARCHIVE'") == "0", "Archive replay cannot resurrect cleared state")
    raw_upload(); raw_upload(trickle=True)

    with futures.ThreadPoolExecutor(max_workers=8) as pool:
        statuses = list(pool.map(lambda _: request(18081, "/statistics")[0], range(180)))
    check(429 in statuses and all(s in (200,429) for s in statuses), "HTTP burst is bounded with explicit 429 responses")
    sql("SELECT setval(pg_get_serial_sequence('alarm_history','id'),5000,true)")
    check(request(18082, "", event("capacity", "CAPACITY"))[0] == 507, "Lifetime storage capacity returns 507 without success")
    check(sql("SELECT count(*) FROM event_receipt WHERE event_id='capacity'") == "0", "Capacity rejection leaves no receipt")
    (WORK/"results.json").write_text(json.dumps(dict(database=DB, checks=RESULTS), indent=2))
    print(f"SUCCESS {len(RESULTS)} end-to-end checks; report {WORK/'results.json'}", flush=True)


if __name__ == "__main__":
    try:
        main()
    finally:
        for process in PROCESSES:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        for log in LOGS: log.close()
