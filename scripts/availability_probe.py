#!/usr/bin/env python3
"""Sample EquipOps liveness from an independent process."""
import argparse
from datetime import datetime, timezone
from http.client import HTTPConnection, HTTPException
import json
import os
from pathlib import Path
from socket import SHUT_RDWR
import sqlite3
import sys
from threading import Event, Timer
import time


HEALTH_PATH = "/actuator/health"
MAX_BODY = 8192
FAILURES_TO_OPEN = 3
SUCCESSES_TO_RECOVER = 2


def observe(port):
    # A fixed loopback request avoids proxy settings, credentials and redirects.
    connection = HTTPConnection("127.0.0.1", port, timeout=2)
    timed_out = Event()
    timer = None
    try:
        connection.connect()
        transport = connection.sock

        def expire():
            timed_out.set()
            try:
                transport.shutdown(SHUT_RDWR)
            except OSError:
                pass

        # Bound the entire HTTP exchange, including responses that trickle bytes.
        timer = Timer(2, expire)
        timer.daemon = True
        timer.start()
        result = read_health(connection)
        return "TIMEOUT" if timed_out.is_set() else result
    except TimeoutError:
        return "TIMEOUT"
    except OSError:
        return "TIMEOUT" if timed_out.is_set() else "CONNECT_FAILED"
    except (HTTPException, UnicodeError, ValueError, RecursionError):
        return "TIMEOUT" if timed_out.is_set() else "HEALTH_INVALID_RESPONSE"
    finally:
        if timer is not None:
            timer.cancel()
        connection.close()


def read_health(connection):
    connection.request("GET", HEALTH_PATH, headers={"Accept": "application/json"})
    with connection.getresponse() as response:
        if response.status not in (200, 503):
            return "HTTP_UNEXPECTED_STATUS"
        body = response.read(MAX_BODY + 1)
        if len(body) > MAX_BODY:
            return "HEALTH_INVALID_RESPONSE"
        payload = json.loads(body)
        status = payload.get("status") if isinstance(payload, dict) else None
        if status == "UP" and response.status == 200:
            return "UP"
        if status in ("DOWN", "OUT_OF_SERVICE"):
            return "HEALTH_STATUS_DOWN"
        return "HEALTH_INVALID_RESPONSE"


def open_state(path, read_only=False):
    # The operator supplies a private directory. Create a private file before SQLite opens it.
    if read_only:
        database = sqlite3.connect(path.absolute().as_uri() + "?mode=ro", uri=True, timeout=10)
        database.row_factory = sqlite3.Row
        return database
    try:
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        if path.is_symlink() or not path.is_file():
            raise ValueError("state must be a regular file") from None
    else:
        os.close(descriptor)
    database = sqlite3.connect(path, timeout=10)
    database.row_factory = sqlite3.Row
    return database


def read_or_initialize(database, port):
    # Execute DDL separately so the caller's transaction also protects initialization.
    database.execute("""
        CREATE TABLE IF NOT EXISTS probe_state (
            id INTEGER PRIMARY KEY CHECK(id = 1), port INTEGER NOT NULL,
            sampled_at TEXT, last_result TEXT, failures INTEGER NOT NULL DEFAULT 0,
            successes INTEGER NOT NULL DEFAULT 0, incident_open INTEGER NOT NULL DEFAULT 0
        )
    """)
    database.execute("""
        CREATE TABLE IF NOT EXISTS probe_events (
            id INTEGER PRIMARY KEY, kind TEXT NOT NULL, sampled_at TEXT NOT NULL, evidence TEXT NOT NULL
        )
    """)
    database.execute("INSERT OR IGNORE INTO probe_state(id, port) VALUES (1, ?)", (port,))
    state = database.execute("SELECT * FROM probe_state WHERE id = 1").fetchone()
    if state["port"] != port:
        raise ValueError("state already belongs to a different management port")
    return state


def sample(database, state, port):
    result = observe(port)
    sampled_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds")
    good = result == "UP"
    failures = 0 if good else min(state["failures"] + 1, FAILURES_TO_OPEN)
    successes = min(state["successes"] + 1, SUCCESSES_TO_RECOVER) if good else 0
    opened = bool(state["incident_open"])
    if not opened and failures == FAILURES_TO_OPEN:
        opened = True
    elif opened and successes == SUCCESSES_TO_RECOVER:
        opened = False
    if opened != bool(state["incident_open"]):
        database.execute("INSERT INTO probe_events(kind, sampled_at, evidence) VALUES (?, ?, ?)",
                         ("OPEN" if opened else "RECOVERED", sampled_at, result))
    database.execute("""
        UPDATE probe_state SET sampled_at = ?, last_result = ?, failures = ?,
            successes = ?, incident_open = ? WHERE id = 1
    """, (sampled_at, result, failures, successes, opened))
    return {"sampled_at": sampled_at, "last_result": result, "incident_open": opened}


def execute(database, port, operation, interval=None):
    # Serialize observation and decision. A process exit rolls back an unfinished observation.
    with database:
        if operation == "sample":
            database.execute("BEGIN IMMEDIATE")
            state = read_or_initialize(database, port)
            if interval is not None and state["sampled_at"] is not None:
                elapsed = (datetime.now(timezone.utc) - datetime.fromisoformat(state["sampled_at"])).total_seconds()
                if 0 <= elapsed < interval:
                    return None
            return sample(database, state, port)
        database.execute("BEGIN")
        state = database.execute("SELECT * FROM probe_state WHERE id = 1").fetchone()
        if state is None or state["port"] != port:
            raise ValueError("state does not match the requested management port")
        events = database.execute("""
            SELECT id, kind, sampled_at, evidence FROM probe_events ORDER BY id DESC LIMIT 100
        """).fetchall()
        return {"state": dict(state) | {"incident_open": bool(state["incident_open"])},
                "events": [dict(event) for event in reversed(events)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("sample", "status", "run"))
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--port", default=9093, type=int)
    parser.add_argument("--interval", default=10, type=int, help="Seconds between background samples")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 2 <= args.interval <= 3600:
        parser.error("port or interval out of range")

    try:
        database = open_state(args.state, read_only=args.operation == "status")
        try:
            if args.operation == "run":
                while True:
                    execute(database, args.port, "sample", args.interval)
                    time.sleep(args.interval)
            else:
                print(json.dumps(execute(database, args.port, args.operation)))
        finally:
            database.close()
    except KeyboardInterrupt:
        return 130
    except (OSError, sqlite3.Error, ValueError):
        print("Probe state unavailable or does not match the target", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
