"""Lưu trữ SQLite."""
from __future__ import annotations

import sqlite3
import threading
import time
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS payments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token TEXT NOT NULL UNIQUE,
    ref_code TEXT NOT NULL UNIQUE,
    order_code TEXT NOT NULL,
    amount INTEGER NOT NULL,
    paid_amount INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'pending',   -- pending | partial | paid | cancelled
    note TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    expires_at INTEGER,                        -- NULL = không hết hạn
    paid_at INTEGER,
    erp_notified INTEGER NOT NULL DEFAULT 0,
    erp_error TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_payments_order ON payments(order_code);

CREATE TABLE IF NOT EXISTS bank_txns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    txn_key TEXT NOT NULL UNIQUE,
    bank TEXT NOT NULL DEFAULT '',
    amount INTEGER NOT NULL,
    direction TEXT NOT NULL,                  -- in | out
    content TEXT NOT NULL DEFAULT '',
    balance INTEGER,
    txn_time INTEGER,
    received_at INTEGER NOT NULL,
    raw TEXT NOT NULL DEFAULT '',
    match_status TEXT NOT NULL,
    payment_id INTEGER REFERENCES payments(id),
    resolved INTEGER NOT NULL DEFAULT 0,
    resolution TEXT NOT NULL DEFAULT '',
    note TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_txns_status ON bank_txns(match_status, resolved);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    at INTEGER NOT NULL,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    payment_id INTEGER,
    txn_id INTEGER,
    detail TEXT NOT NULL DEFAULT ''
);
"""


def now() -> int:
    """Thời gian hiện tại (giây). Tách riêng để test có thể thay thế."""
    return int(time.time())


class Database:
    def __init__(self, path: str):
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys = ON")
        self._conn.execute("PRAGMA journal_mode = WAL")
        self._conn.executescript(SCHEMA)
        self.lock = threading.RLock()

    def execute(self, sql: str, params: tuple = ()) -> sqlite3.Cursor:
        with self.lock:
            cur = self._conn.execute(sql, params)
            self._conn.commit()
            return cur

    def one(self, sql: str, params: tuple = ()) -> dict[str, Any] | None:
        with self.lock:
            row = self._conn.execute(sql, params).fetchone()
            return dict(row) if row else None

    def all(self, sql: str, params: tuple = ()) -> list[dict[str, Any]]:
        with self.lock:
            return [dict(r) for r in self._conn.execute(sql, params).fetchall()]

    def log(self, actor: str, action: str, payment_id=None, txn_id=None, detail="") -> None:
        self.execute(
            "INSERT INTO audit_log (at, actor, action, payment_id, txn_id, detail) VALUES (?,?,?,?,?,?)",
            (now(), actor, action, payment_id, txn_id, detail),
        )
