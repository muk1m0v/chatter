"""Google-only verified identities and revocable Salom device sessions."""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import threading
import time
import uuid
from urllib.parse import urlparse

from cachecontrol import CacheControl
from fastapi import Header, HTTPException, Query, Request
from google.auth.transport.requests import Request as GoogleRequest
from google.oauth2 import id_token
from pydantic import BaseModel, ConfigDict, Field
import requests

USERNAME = re.compile(r'^[A-Za-z][A-Za-z0-9_]{3,23}$')
EMAIL = re.compile(r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
firebase_request = GoogleRequest(session=CacheControl(requests.Session()))
firebase_lock = threading.Lock()

def schema(db):
    # Keep previous columns intact so upgrading never discards existing identities.
    db.executescript('''
      CREATE TABLE IF NOT EXISTS accounts (
        profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
        firebase_uid TEXT UNIQUE, phone TEXT UNIQUE, email TEXT UNIQUE,
        password_hash TEXT, created REAL NOT NULL, password_changed REAL);
      CREATE TABLE IF NOT EXISTS sessions (
        token_hash TEXT PRIMARY KEY, profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
        created REAL NOT NULL, expires REAL NOT NULL, verified_at REAL NOT NULL);
      CREATE INDEX IF NOT EXISTS sessions_profile ON sessions(profile_id);
      CREATE TABLE IF NOT EXISTS auth_limits (key TEXT NOT NULL, at REAL NOT NULL);
      CREATE INDEX IF NOT EXISTS auth_limits_key ON auth_limits(key,at);
    ''')
    columns = {row[1] for row in db.execute('PRAGMA table_info(accounts)')}
    if 'google_linked' not in columns:
        db.execute('ALTER TABLE accounts ADD COLUMN google_linked INTEGER NOT NULL DEFAULT 0')

def hash_token(value): return hashlib.sha256(value.encode()).hexdigest()

def limited(connect, key, maximum, seconds):
    now = time.time()
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        db.execute('DELETE FROM auth_limits WHERE at<?', (now - 86400,))
        count = db.execute('SELECT count(*) FROM auth_limits WHERE key=? AND at>?', (key, now - seconds)).fetchone()[0]
        if count >= maximum: raise HTTPException(429, 'Слишком много попыток. Попробуй позже')
        db.execute('INSERT INTO auth_limits VALUES(?,?)', (key, now))

def ip_key(request): return hash_token(request.client.host if request.client else 'unknown')

def firebase_identity(token):
    if not token: raise HTTPException(401, 'Сначала войди через Google')
    try:
        credential_path = os.environ.get('GOOGLE_APPLICATION_CREDENTIALS', '/etc/salom-api/firebase-admin.json')
        project = json.loads(Path(credential_path).read_text())['project_id']
        with firebase_lock:
            claims = id_token.verify_firebase_token(token, firebase_request, audience=project)
        now = time.time()
        if claims.get('firebase', {}).get('sign_in_provider') != 'google.com': raise ValueError()
        email = claims.get('email', '').strip().lower()
        if claims.get('email_verified') is not True or len(email) > 254 or not EMAIL.fullmatch(email): raise ValueError()
        if not claims.get('sub') or not 0 <= now - claims.get('auth_time', 0) <= 900: raise ValueError()
        picture = claims.get('picture', '')
        parsed = urlparse(picture) if isinstance(picture, str) else None
        avatar_url = picture if parsed and parsed.scheme == 'https' and (parsed.hostname or '').endswith('googleusercontent.com') and len(picture) < 2048 else ''
        return {'email': email, 'firebase_uid': claims['sub'], 'avatar_url': avatar_url}
    except Exception:
        raise HTTPException(401, 'Вход Google недействителен или устарел. Выбери аккаунт ещё раз')

class IdentityProof(BaseModel):
    model_config = ConfigDict(extra='forbid')
    firebase_token: str = Field(min_length=1, max_length=16384)
    name: str = Field(default='', max_length=80)
    username: str = Field(default='', max_length=24)
    bind_current: bool = False

def install_routes(app, connect, current_profile, public, digest):
    def account_json(db, profile_id):
        row = db.execute('SELECT * FROM accounts WHERE profile_id=?', (profile_id,)).fetchone()
        linked = bool(row and row['google_linked'])
        return {'verified': linked, 'google_linked': linked, 'email': (row['email'] or '') if row else ''}

    def session(db, profile_id):
        profile = db.execute('SELECT * FROM profiles WHERE id=?', (profile_id,)).fetchone()
        if not profile or not profile['is_active']: raise HTTPException(403, 'Аккаунт временно недоступен')
        token, now = secrets.token_urlsafe(32), time.time()
        db.execute('DELETE FROM sessions WHERE expires<?', (now,))
        db.execute('INSERT INTO sessions VALUES(?,?,?,?,?)', (hash_token(token), profile_id, now, now + 90 * 86400, now))
        return {'profile': public(profile), 'session_token': token, **account_json(db, profile_id)}

    @app.get('/auth/config')
    def config():
        return {'google_enabled': True, 'email_enabled': False, 'sms_enabled': False}

    @app.get('/auth/username')
    def username_available(request: Request, username: str = Query(min_length=1, max_length=24)):
        limited(connect, 'username:' + ip_key(request), 180, 60)
        if not USERNAME.fullmatch(username): return {'available': False, 'valid': False}
        with connect() as db:
            found = db.execute('SELECT 1 FROM profiles WHERE username_key=?', (username.lower(),)).fetchone()
        return {'available': not bool(found), 'valid': True}

    @app.post('/auth/identity')
    def exchange(proof: IdentityProof, request: Request, authorization: str | None = Header(default=None)):
        limited(connect, 'identity-ip:' + ip_key(request), 30, 60)
        contact = firebase_identity(proof.firebase_token)
        owner = None
        if proof.bind_current:
            try: owner = current_profile(authorization)
            except HTTPException as error:
                if error.status_code != 401: raise
        now = time.time()
        with connect() as db:
            db.execute('BEGIN IMMEDIATE')
            existing = db.execute('SELECT * FROM accounts WHERE firebase_uid=?', (contact['firebase_uid'],)).fetchone()
            email_owner = db.execute('SELECT * FROM accounts WHERE email=?', (contact['email'],)).fetchone()
            # Never merge different Firebase identities on a mutable email claim.
            if email_owner and email_owner['firebase_uid'] not in (None, contact['firebase_uid']):
                raise HTTPException(409, 'Этот email уже связан с другим аккаунтом Салом')
            if not owner and not existing and email_owner and email_owner['firebase_uid'] is None:
                raise HTTPException(409, 'Сначала подключи Google из существующего профиля Салом')
            if owner:
                target = owner['id']
                valid_owner = db.execute('SELECT 1 FROM profiles p WHERE p.id=? AND p.is_active=1 AND (p.token_hash=? OR EXISTS(SELECT 1 FROM sessions s WHERE s.profile_id=p.id AND s.token_hash=? AND s.expires>?))',
                                        (target, digest(authorization), digest(authorization), now)).fetchone()
                if not valid_owner: raise HTTPException(401, 'Сессия истекла. Войди через Google заново')
                if existing and existing['profile_id'] != target: raise HTTPException(409, 'Этот Google-аккаунт уже подключён к другому профилю Салом')
                if email_owner and email_owner['profile_id'] != target: raise HTTPException(409, 'Этот email уже связан с другим профилем')
                old = db.execute('SELECT * FROM accounts WHERE profile_id=?', (target,)).fetchone()
                if old and old['firebase_uid'] and old['firebase_uid'] != contact['firebase_uid']:
                    raise HTTPException(409, 'К профилю уже подключён другой аккаунт Google')
                if not owner['username']:
                    if not proof.username: return {'needs_username': True}
                    if not USERNAME.fullmatch(proof.username): raise HTTPException(400, 'Проверь username')
                    try: db.execute('UPDATE profiles SET username=?,username_key=?,updated=? WHERE id=?', (proof.username, proof.username.lower(), now, target))
                    except sqlite3.IntegrityError: raise HTTPException(409, 'Этот username уже занят. Выбери другой')
            elif existing:
                if contact['avatar_url']:
                    db.execute('UPDATE profiles SET avatar_url=? WHERE id=?', (contact['avatar_url'], existing['profile_id']))
                if not existing['google_linked']:
                    db.execute('UPDATE accounts SET google_linked=1,email=? WHERE profile_id=?', (contact['email'], existing['profile_id']))
                else:
                    db.execute('UPDATE accounts SET email=? WHERE profile_id=?', (contact['email'], existing['profile_id']))
                return session(db, existing['profile_id'])
            else:
                if proof.bind_current: raise HTTPException(401, 'Сессия истекла. Открой сохранённый профиль и повтори подключение')
                if not proof.username: return {'needs_username': True}
                if not USERNAME.fullmatch(proof.username): raise HTTPException(400, 'Username: 4–24 латинские буквы, цифры и _, начинается с буквы')
                name = proof.name.strip()
                if not name: raise HTTPException(400, 'Укажи имя')
                target = str(uuid.uuid4())
                try:
                    db.execute('INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)', (target, hash_token(secrets.token_urlsafe(32)), name, proof.username, proof.username.lower(), now, now))
                except sqlite3.IntegrityError: raise HTTPException(409, 'Этот username уже занят. Выбери другой')
            try:
                if contact['avatar_url']:
                    db.execute('UPDATE profiles SET avatar_url=? WHERE id=?', (contact['avatar_url'], target))
                db.execute('INSERT INTO accounts(profile_id,firebase_uid,email,created,google_linked) VALUES(?,?,?,?,1) ON CONFLICT(profile_id) DO UPDATE SET firebase_uid=excluded.firebase_uid,email=excluded.email,google_linked=1',
                           (target, contact['firebase_uid'], contact['email'], now))
            except sqlite3.IntegrityError: raise HTTPException(409, 'Google уже связан с другим аккаунтом')
            # Upgrade ownership without changing profile ID or its message history.
            db.execute('UPDATE profiles SET token_hash=? WHERE id=?', (hash_token(secrets.token_urlsafe(32)), target))
            return session(db, target)

    @app.get('/auth/me')
    def me(authorization: str | None = Header(default=None)):
        profile = current_profile(authorization)
        with connect() as db: return account_json(db, profile['id'])

    @app.post('/auth/logout')
    def logout(authorization: str | None = Header(default=None)):
        profile = current_profile(authorization)
        with connect() as db:
            db.execute('DELETE FROM sessions WHERE token_hash=?', (digest(authorization),))
            db.execute('DELETE FROM push_tokens WHERE profile_id=?', (profile['id'],))
        return {'signed_out': True}
