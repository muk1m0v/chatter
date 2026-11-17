"""Small psycopg adapter preserving the current synchronous SQLite call surface."""
import os
import re
import sqlite3
from typing import Any

import psycopg
from psycopg_pool import ConnectionPool


INTEGRITY_ERRORS = (sqlite3.IntegrityError, psycopg.IntegrityError)


class HybridRow(dict):
    """Mapping row with SQLite-compatible numeric indexes for old call sites."""
    def __getitem__(self, key):
        if isinstance(key, int):
            return tuple(self.values())[key]
        return super().__getitem__(key)


def _row_factory(cursor):
    if cursor.description is None:
        return lambda values: values
    names = tuple(column.name for column in cursor.description)
    return lambda values: HybridRow(zip(names, values))


class CursorAdapter:
    def __init__(self, cursor, lastrowid=None):
        self._cursor = cursor
        self.lastrowid = lastrowid

    def fetchone(self): return self._cursor.fetchone()
    def fetchall(self): return self._cursor.fetchall()
    def fetchmany(self, size=None): return self._cursor.fetchmany(size) if size else self._cursor.fetchmany()
    def __iter__(self): return iter(self._cursor)
    def __getattr__(self, name): return getattr(self._cursor, name)


class PostgresConnection:
    def __init__(self, connection):
        self._connection = connection

    @staticmethod
    def _sql(sql: str) -> str:
        normalized = sql.strip()
        if normalized.upper().startswith("PRAGMA "):
            return ""
        if normalized.upper() == "BEGIN IMMEDIATE":
            return "BEGIN"
        normalized = re.sub(r"(?i)\bMAX\(through_id\s*,\s*excluded\.through_id\)", "GREATEST(conversation_clears.through_id,excluded.through_id)", normalized)
        ignored = re.match(r"(?is)^INSERT\s+OR\s+IGNORE\s+INTO\s+", normalized)
        if ignored:
            normalized = re.sub(r"(?is)^INSERT\s+OR\s+IGNORE\s+INTO\s+", "INSERT INTO ", normalized, count=1).rstrip(";") + " ON CONFLICT DO NOTHING"
        normalized = normalized.replace("?", "%s")
        if re.match(r"(?is)^INSERT\s+INTO\s+messages\b", normalized) and " RETURNING " not in normalized.upper():
            normalized = normalized.rstrip(";") + " RETURNING id"
        return normalized

    def execute(self, sql: str, parameters: Any = ()) -> CursorAdapter:
        normalized = self._sql(sql)
        if not normalized:
            return CursorAdapter(self._connection.execute("SELECT 1 WHERE FALSE"))
        cursor = self._connection.cursor(row_factory=_row_factory)
        cursor.execute(normalized, parameters)
        lastrowid = None
        if re.match(r"(?is)^INSERT\s+INTO\s+messages\b", normalized) and " RETURNING " in normalized.upper():
            row = cursor.fetchone()
            lastrowid = row["id"] if row else None
        return CursorAdapter(cursor, lastrowid)

    def executescript(self, script: str) -> None:
        for statement in script.split(";"):
            if statement.strip():
                self.execute(statement)


def create_pool(dsn: str) -> ConnectionPool:
    try:
        max_size = int(os.environ.get("SALOM_DB_POOL_SIZE", "4"))
    except ValueError as error:
        raise ValueError("SALOM_DB_POOL_SIZE must be an integer from 1 to 8") from error
    if not 1 <= max_size <= 8:
        raise ValueError("SALOM_DB_POOL_SIZE must be from 1 to 8")
    pool = ConnectionPool(dsn, min_size=1, max_size=max_size, timeout=5, max_waiting=16, open=False, name="salom-api")
    pool.open(wait=True, timeout=10)
    return pool
