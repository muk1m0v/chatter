"""Salom profiles, messaging and verified accounts. Address books are never collected."""
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
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ConfigDict, Field
import media
from push import PushDispatcher
from auth import schema as auth_schema, install_routes as install_auth_routes

DB = Path(os.environ.get('SALOM_DB', '/var/lib/salom-api/directory.sqlite3'))
USERNAME = re.compile(r'^[A-Za-z][A-Za-z0-9_]{3,23}$')
TOKEN = re.compile(r'^[A-Za-z0-9_-]{43,128}$')
rate_lock = threading.Lock()
rates = defaultdict(deque)

def connect():
    db = sqlite3.connect(DB, timeout=10)
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
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
        CREATE TABLE IF NOT EXISTS messages (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          sender_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
          recipient_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
          client_id TEXT NOT NULL, text TEXT NOT NULL, created REAL NOT NULL,
          UNIQUE(sender_id, client_id));
        CREATE INDEX IF NOT EXISTS messages_pair ON messages(sender_id, recipient_id, id);
        CREATE INDEX IF NOT EXISTS messages_recipient ON messages(recipient_id, id);
        CREATE TABLE IF NOT EXISTS push_tokens (
          profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
          token TEXT NOT NULL UNIQUE, updated REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS push_jobs (
          message_id INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
          state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
          next_at REAL NOT NULL DEFAULT 0, last_error TEXT);
        CREATE INDEX IF NOT EXISTS push_jobs_pending ON push_jobs(state,next_at);
        ''')
        auth_schema(db)
        media.schema(db)

@asynccontextmanager
async def lifespan(app):
    initialize()
    app.state.push = PushDispatcher(connect)
    app.state.push.start()
    yield
    app.state.push.stop()

app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

@app.exception_handler(RequestValidationError)
async def validation_error(request, exception):
    # Validation responses must not echo submitted passwords, verification codes or tokens.
    return JSONResponse({'detail': 'Проверь введённые данные'}, status_code=422)

@app.middleware('http')
async def bounds(request: Request, call_next):
    if int(request.headers.get('content-length', '0') or 0) > (media.MAX_FILE if request.url.path == '/attachments' else 65536):
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
    return {'status': 'ok', 'service': 'salom-directory', 'push_configured': app.state.push.configured}

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
        if not existing:
            raise HTTPException(426, 'Обнови Салом и войди через Google для регистрации')
        if existing and not hmac.compare_digest(existing['token_hash'], hashed):
            session = db.execute('SELECT profile_id FROM sessions WHERE token_hash=? AND expires>?', (hashed, now)).fetchone()
            if not session or session['profile_id'] != body.device_id:
                raise HTTPException(403, 'Этот профиль принадлежит другому устройству')
        if existing and db.execute('SELECT 1 FROM accounts WHERE profile_id=?', (body.device_id,)).fetchone() and not username:
            raise HTTPException(400, 'У подтверждённого аккаунта должен быть username')
        if existing and username.lower() != (existing['username_key'] or '') and not db.execute('SELECT 1 FROM accounts WHERE profile_id=?', (body.device_id,)).fetchone():
            raise HTTPException(403, 'Подключи Google перед выбором нового username')
        try:
            db.execute('UPDATE profiles SET name=?,username=?,username_key=?,updated=? WHERE id=?', (name, username or None, username.lower() or None, now, body.device_id))
        except sqlite3.IntegrityError:
            raise HTTPException(409, 'Этот username уже занят. Выбери другой')
        return public(db.execute('SELECT * FROM profiles WHERE id=?', (body.device_id,)).fetchone())

@app.get('/search')
def search(q: str = Query(min_length=1, max_length=64), authorization: str | None = Header(default=None)):
    current_profile(authorization)
    with connect() as db:
        prefix = q.strip().removeprefix('@').lower()
        if not re.fullmatch(r'[a-z0-9_]{1,24}', prefix): return {'profiles': []}
        pattern = prefix.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'
        rows = db.execute("SELECT * FROM profiles WHERE is_active=1 AND username_key LIKE ? ESCAPE '\\' ORDER BY CASE WHEN username_key=? THEN 0 ELSE 1 END, username_key LIMIT 20", (pattern, prefix)).fetchall()
        return {'profiles': [public(row) for row in rows]}

def current_profile(authorization):
    hashed = digest(authorization)
    with connect() as db:
        row = db.execute('SELECT * FROM profiles WHERE token_hash=? AND is_active=1', (hashed,)).fetchone()
        if not row:
            row = db.execute('SELECT p.* FROM profiles p JOIN sessions s ON s.profile_id=p.id WHERE s.token_hash=? AND s.expires>? AND p.is_active=1', (hashed, time.time())).fetchone()
        if not row: raise HTTPException(401, 'Требуется активный профиль')
        throttle(hashed)
        return dict(row)

install_auth_routes(app, connect, current_profile, public, digest)
media.install_routes(app, connect, current_profile)

def message_json(row, db):
    return {**{key: row[key] for key in ('id', 'sender_id', 'recipient_id', 'text', 'created')}, 'attachment': media.metadata(db, row['attachment_id'])}

class MessageInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    recipient_id: str = Field(min_length=36, max_length=36)
    client_id: str = Field(min_length=36, max_length=36)
    text: str = Field(default='', max_length=4000)
    attachment_id: str | None = Field(default=None, min_length=36, max_length=36)

@app.post('/messages')
def send_message(body: MessageInput, authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    try:
        UUID(body.recipient_id)
        UUID(body.client_id)
    except ValueError: raise HTTPException(400, 'Неверный идентификатор сообщения или получателя')
    text = body.text.strip()
    if not text and not body.attachment_id: raise HTTPException(400, 'Сообщение не может быть пустым')
    if body.recipient_id == me['id']: raise HTTPException(400, 'Для личных заметок используй Избранное')
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        if not db.execute('SELECT id FROM profiles WHERE id=? AND is_active=1', (body.recipient_id,)).fetchone():
            raise HTTPException(404, 'Получатель недоступен')
        existing = db.execute('SELECT * FROM messages WHERE sender_id=? AND client_id=?', (me['id'], body.client_id)).fetchone()
        if existing:
            if existing['recipient_id'] != body.recipient_id or existing['text'] != text or existing['attachment_id'] != body.attachment_id:
                raise HTTPException(409, 'Этот идентификатор уже использован для другого сообщения')
            return message_json(existing, db)
        if body.attachment_id:
            attachment = db.execute('SELECT * FROM attachments WHERE id=? AND owner_id=? AND peer_id=? AND ready=1', (body.attachment_id, me['id'], body.recipient_id)).fetchone()
            if not attachment: raise HTTPException(400, 'Вложение недоступно')
            if db.execute('SELECT 1 FROM messages WHERE attachment_id=?', (body.attachment_id,)).fetchone(): raise HTTPException(409, 'Вложение уже отправлено')
        cursor = db.execute('INSERT INTO messages(sender_id,recipient_id,client_id,text,created,attachment_id) VALUES(?,?,?,?,?,?)', (me['id'], body.recipient_id, body.client_id, text, time.time(), body.attachment_id))
        db.execute('INSERT INTO push_jobs(message_id) VALUES(?)', (cursor.lastrowid,))
        app.state.push.wake.set()
        return message_json(db.execute('SELECT * FROM messages WHERE id=?', (cursor.lastrowid,)).fetchone(), db)

class PushTokenInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    token: str = Field(min_length=20, max_length=4096, pattern=r'^[A-Za-z0-9_:\-]+$')
    enabled: bool = True

@app.put('/push-token')
def register_push(body: PushTokenInput, authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    with connect() as db:
        if not body.enabled:
            db.execute('DELETE FROM push_tokens WHERE profile_id=?', (me['id'],))
        else:
            try:
                db.execute('INSERT INTO push_tokens(profile_id,token,updated) VALUES(?,?,?) ON CONFLICT(profile_id) DO UPDATE SET token=excluded.token,updated=excluded.updated',
                           (me['id'], body.token, time.time()))
            except sqlite3.IntegrityError:
                raise HTTPException(409, 'Этот токен уже связан с другим профилем')
    return {'registered': body.enabled}

@app.get('/profiles/{peer_id}')
def get_profile(peer_id: str, authorization: str | None = Header(default=None)):
    current_profile(authorization)
    with connect() as db:
        row = db.execute('SELECT * FROM profiles WHERE id=? AND is_active=1', (peer_id,)).fetchone()
        if not row: raise HTTPException(404, 'Профиль недоступен')
        return public(row)

@app.get('/messages')
def get_messages(peer_id: str, after: int = Query(default=0, ge=0), authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    with connect() as db:
        cleared = media.cutoff(db, me['id'], peer_id)
        rows = db.execute('''SELECT * FROM messages WHERE id>? AND
          ((sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?))
          ORDER BY id LIMIT 100''', (max(after, cleared), me['id'], peer_id, peer_id, me['id'])).fetchall()
        return {'messages': [message_json(row, db) for row in rows], 'has_more': len(rows) == 100, 'cleared_through': cleared}

@app.get('/conversations')
def conversations(authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    with connect() as db:
        rows = db.execute('''WITH latest AS (
          SELECT CASE WHEN sender_id=? THEN recipient_id ELSE sender_id END AS peer_id, MAX(id) AS last_id
          FROM messages WHERE (sender_id=? OR recipient_id=?) AND id>COALESCE((SELECT through_id FROM conversation_clears c WHERE c.owner_id=? AND c.peer_id=CASE WHEN sender_id=? THEN recipient_id ELSE sender_id END),0) GROUP BY peer_id ORDER BY last_id DESC LIMIT 100)
          SELECT p.*, COALESCE(NULLIF(m.text,''),CASE WHEN a.kind='voice' THEN 'Голосовое сообщение' ELSE '📎 Вложение' END) AS last_text, m.id AS last_message_id FROM latest l
          JOIN profiles p ON p.id=l.peer_id JOIN messages m ON m.id=l.last_id LEFT JOIN attachments a ON a.id=m.attachment_id ORDER BY l.last_id DESC''', (me['id'], me['id'], me['id'], me['id'], me['id'])).fetchall()
        return {'conversations': [{'profile': public(row), 'last_text': row['last_text'], 'last_message_id': row['last_message_id']} for row in rows]}
