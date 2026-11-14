"""Private bounded attachments; only the sender and visible conversation recipient may read."""
import os
from pathlib import Path
import re
import shutil
import time
import uuid
from fastapi import Header, HTTPException, Query, Request
from fastapi.responses import FileResponse

ROOT = Path(os.environ.get('SALOM_MEDIA', '/var/lib/salom-api/attachments'))
MAX_FILE = 25 * 1024 * 1024
MAX_ACCOUNT = 200 * 1024 * 1024

def schema(db):
    db.executescript('''CREATE TABLE IF NOT EXISTS attachments(
      id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
      peer_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
      name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, created REAL NOT NULL,
      ready INTEGER NOT NULL DEFAULT 0);
      CREATE INDEX IF NOT EXISTS attachments_owner ON attachments(owner_id);
      CREATE TABLE IF NOT EXISTS conversation_clears(
      owner_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
      peer_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
      through_id INTEGER NOT NULL, PRIMARY KEY(owner_id,peer_id));''')
    if 'attachment_id' not in {row[1] for row in db.execute('PRAGMA table_info(messages)')}:
        db.execute('ALTER TABLE messages ADD COLUMN attachment_id TEXT REFERENCES attachments(id)')

def metadata(db, attachment_id):
    if not attachment_id: return None
    row = db.execute('SELECT id,name,mime,size FROM attachments WHERE id=? AND ready=1', (attachment_id,)).fetchone()
    return dict(row) if row else None

def cutoff(db, owner, peer):
    row = db.execute('SELECT through_id FROM conversation_clears WHERE owner_id=? AND peer_id=?', (owner, peer)).fetchone()
    return row[0] if row else 0

def install_routes(app, connect, current_profile):
    @app.post('/attachments')
    async def upload(request: Request, peer_id: str = Query(min_length=36, max_length=36), name: str = Query(min_length=1, max_length=180), authorization: str | None = Header(default=None)):
        owner = current_profile(authorization)['id']
        try: uuid.UUID(peer_id)
        except ValueError: raise HTTPException(400, 'Неверный получатель')
        if owner == peer_id: raise HTTPException(400, 'Используй Избранное для личных файлов')
        filename = name.replace('\\', '/').split('/')[-1]
        filename = ''.join(c for c in filename if c.isprintable()).strip()[:180] or 'Файл'
        mime = request.headers.get('content-type', 'application/octet-stream').split(';')[0].lower()
        if not re.fullmatch(r'[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+', mime): mime = 'application/octet-stream'
        try: reserved = int(request.headers.get('content-length', str(MAX_FILE)))
        except ValueError: raise HTTPException(400, 'Неверный размер файла')
        if reserved <= 0 or reserved > MAX_FILE: raise HTTPException(413, 'Максимальный размер файла — 25 МБ')
        ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
        if shutil.disk_usage(ROOT).free < MAX_FILE + 512 * 1024 * 1024: raise HTTPException(503, 'Хранилище временно недоступно')
        attachment_id = str(uuid.uuid4())
        with connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if not db.execute('SELECT 1 FROM profiles WHERE id=? AND is_active=1', (peer_id,)).fetchone(): raise HTTPException(404, 'Получатель недоступен')
            stale = db.execute('SELECT id FROM attachments WHERE created<? AND NOT EXISTS(SELECT 1 FROM messages WHERE attachment_id=attachments.id)', (time.time()-86400,)).fetchall()
            for row in stale:
                (ROOT / row['id']).unlink(missing_ok=True)
                db.execute('DELETE FROM attachments WHERE id=?', (row['id'],))
            used = db.execute('SELECT COALESCE(SUM(size),0) FROM attachments WHERE owner_id=?', (owner,)).fetchone()[0]
            if used + reserved > MAX_ACCOUNT: raise HTTPException(413, 'Лимит вложений аккаунта — 200 МБ')
            db.execute('INSERT INTO attachments VALUES(?,?,?,?,?,?,?,0)', (attachment_id, owner, peer_id, filename, mime, reserved, time.time()))
        path = ROOT / attachment_id
        complete = False
        try:
            total = 0
            with path.open('xb') as output:
                path.chmod(0o600)
                async for chunk in request.stream():
                    total += len(chunk)
                    if total > reserved or total > MAX_FILE: raise HTTPException(413, 'Максимальный размер файла — 25 МБ')
                    output.write(chunk)
            if total == 0: raise HTTPException(400, 'Файл пустой')
            with connect() as db:
                db.execute('UPDATE attachments SET size=?,ready=1 WHERE id=?', (total, attachment_id))
                result = metadata(db, attachment_id)
            complete = True
            return result
        finally:
            if not complete:
                path.unlink(missing_ok=True)
                with connect() as db: db.execute('DELETE FROM attachments WHERE id=?', (attachment_id,))

    @app.get('/attachments/{attachment_id}')
    def download(attachment_id: uuid.UUID, authorization: str | None = Header(default=None)):
        owner = current_profile(authorization)['id']
        with connect() as db:
            attachment = db.execute('SELECT * FROM attachments WHERE id=? AND ready=1', (str(attachment_id),)).fetchone()
            if not attachment: raise HTTPException(404, 'Файл недоступен')
            peer = attachment['peer_id'] if attachment['owner_id'] == owner else attachment['owner_id']
            message = db.execute('SELECT id FROM messages WHERE attachment_id=?', (str(attachment_id),)).fetchone()
            allowed = owner in (attachment['owner_id'], attachment['peer_id']) and message and message['id'] > cutoff(db, owner, peer)
            pending_owner = owner == attachment['owner_id'] and not message
            if not (allowed or pending_owner): raise HTTPException(404, 'Файл недоступен')
        path = ROOT / str(attachment_id)
        if not path.is_file(): raise HTTPException(404, 'Файл недоступен')
        return FileResponse(path, media_type=attachment['mime'], filename=attachment['name'], headers={'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff'})

    @app.delete('/messages')
    def clear(peer_id: uuid.UUID, authorization: str | None = Header(default=None)):
        owner = current_profile(authorization)['id']
        peer = str(peer_id)
        with connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if not db.execute('SELECT 1 FROM profiles WHERE id=?', (peer,)).fetchone(): raise HTTPException(404, 'Собеседник недоступен')
            through = db.execute('SELECT COALESCE(MAX(id),0) FROM messages WHERE (sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?)', (owner, peer, peer, owner)).fetchone()[0]
            db.execute('INSERT INTO conversation_clears VALUES(?,?,?) ON CONFLICT(owner_id,peer_id) DO UPDATE SET through_id=MAX(through_id,excluded.through_id)', (owner, peer, through))
        return {'cleared_through': through}
