"""Public username directory. Phone/email/contacts are deliberately not collected."""
import hashlib
import hmac
import os
import re
import sqlite3
import threading
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from pathlib import Path
from uuid import UUID

from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

DB = Path(os.environ.get('SALOM_DB', '/var/lib/salom-api/directory.sqlite3'))
USERNAME = re.compile(r'^[A-Za-z][A-Za-z0-9_]{3,23}$')
TOKEN = re.compile(r'^[A-Za-z0-9_-]{43,128}$')
rate_lock = threading.Lock()
rates = defaultdict(deque)

def connect():
    db = sqlite3.connect(DB, timeout=10)
    db.row_factory = sqlite3.Row
    return db

def initialize():
    DB.parent.mkdir(parents=True, exist_ok=True)
    with connect() as db:
        db.executescript('''PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS profiles (
          id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
          username TEXT, username_key TEXT UNIQUE, is_active INTEGER NOT NULL DEFAULT 1,
          created REAL NOT NULL, updated REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS registrations (ip TEXT NOT NULL, at REAL NOT NULL);
        CREATE INDEX IF NOT EXISTS registrations_ip ON registrations(ip, at);
        ''')

@asynccontextmanager
async def lifespan(app):
    initialize()
    yield

app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

@app.middleware('http')
async def bounds(request: Request, call_next):
    if int(request.headers.get('content-length', '0') or 0) > 8192:
        return JSONResponse({'detail': 'Слишком большой запрос'}, status_code=413)
    response = await call_next(request)
    response.headers['Cache-Control'] = 'no-store'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    return response

def digest(authorization):
    if not authorization or not authorization.startswith('Bearer '):
        raise HTTPException(401, 'Требуется авторизация устройства')
    token = authorization[7:]
    if not TOKEN.fullmatch(token):
        raise HTTPException(401, 'Неверный ключ устройства')
    return hashlib.sha256(token.encode()).hexdigest()

def public(row):
    return {'id': row['id'], 'name': row['name'], 'username': row['username'] or '', 'is_active': bool(row['is_active'])}

def throttle(key, limit=180):
    now = time.monotonic()
    with rate_lock:
        window = rates[key]
        while window and window[0] < now - 60: window.popleft()
        if len(window) >= limit: raise HTTPException(429, 'Слишком много запросов. Попробуй через минуту')
        window.append(now)
        if len(rates) > 10000:
            for stale in [k for k, v in rates.items() if not v or v[-1] < now - 60]: rates.pop(stale, None)

class ProfileInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    device_id: str = Field(min_length=36, max_length=36)
    name: str = Field(min_length=1, max_length=80)
    username: str = Field(default='', max_length=24)

@app.get('/health')
def health():
    with connect() as db: db.execute('SELECT 1').fetchone()
    return {'status': 'ok', 'service': 'salom-directory'}

@app.put('/profile')
def profile(body: ProfileInput, request: Request, authorization: str | None = Header(default=None)):
    hashed = digest(authorization)
    throttle(hashed)
    try: UUID(body.device_id)
    except ValueError: raise HTTPException(400, 'Неверный идентификатор устройства')
    name = body.name.strip()
    username = body.username.strip().removeprefix('@')
    if not name: raise HTTPException(400, 'Укажи имя')
    if username and not USERNAME.fullmatch(username): raise HTTPException(400, 'Username: 4–24 латинские буквы, цифры и _, начинается с буквы')
    now = time.time()
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        existing = db.execute('SELECT * FROM profiles WHERE id=?', (body.device_id,)).fetchone()
        if existing and not hmac.compare_digest(existing['token_hash'], hashed):
            raise HTTPException(403, 'Этот профиль принадлежит другому устройству')
        if not existing:
            ip = request.client.host if request.client else 'unknown'
            db.execute('DELETE FROM registrations WHERE at<?', (now - 86400,))
            if db.execute('SELECT count(*) FROM registrations WHERE ip=? AND at>?', (ip, now - 3600)).fetchone()[0] >= 10:
                raise HTTPException(429, 'Лимит новых профилей. Попробуй позже')
        try:
            if existing:
                db.execute('UPDATE profiles SET name=?,username=?,username_key=?,updated=? WHERE id=?', (name, username or None, username.lower() or None, now, body.device_id))
            else:
                db.execute('INSERT INTO profiles VALUES(?,?,?,?,?,1,?,?)', (body.device_id, hashed, name, username or None, username.lower() or None, now, now))
                db.execute('INSERT INTO registrations VALUES(?,?)', (ip, now))
        except sqlite3.IntegrityError:
            raise HTTPException(409, 'Этот username уже занят. Выбери другой')
        return public(db.execute('SELECT * FROM profiles WHERE id=?', (body.device_id,)).fetchone())

@app.get('/search')
def search(q: str = Query(min_length=1, max_length=64), authorization: str | None = Header(default=None)):
    hashed = digest(authorization)
    with connect() as db:
        if not db.execute('SELECT id FROM profiles WHERE token_hash=? AND is_active=1', (hashed,)).fetchone():
            raise HTTPException(401, 'Сначала синхронизируй свой профиль')
        throttle(hashed)
        prefix = q.strip().removeprefix('@').lower()
        if not re.fullmatch(r'[a-z0-9_]{1,24}', prefix): return {'profiles': []}
        pattern = prefix.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'
        rows = db.execute("SELECT * FROM profiles WHERE is_active=1 AND username_key LIKE ? ESCAPE '\\' ORDER BY CASE WHEN username_key=? THEN 0 ELSE 1 END, username_key LIMIT 20", (pattern, prefix)).fetchall()
        return {'profiles': [public(row) for row in rows]}
