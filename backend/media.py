"""Private bounded attachments; only the sender and visible conversation recipient may read."""
import json
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
    columns = {row[1] for row in db.execute('PRAGMA table_info(attachments)')}
    for name, definition in [('kind', "TEXT NOT NULL DEFAULT 'file'"), ('duration_ms', 'INTEGER NOT NULL DEFAULT 0'), ('waveform', "TEXT NOT NULL DEFAULT '[]'")]:
        if name not in columns: db.execute('ALTER TABLE attachments ADD COLUMN ' + name + ' ' + definition)
    if 'attachment_id' not in {row[1] for row in db.execute('PRAGMA table_info(messages)')}:
        db.execute('ALTER TABLE messages ADD COLUMN attachment_id TEXT REFERENCES attachments(id)')

def metadata(db, attachment_id):
    if not attachment_id: return None
    row = db.execute('SELECT id,name,mime,size,kind,duration_ms,waveform FROM attachments WHERE id=? AND ready=1', (attachment_id,)).fetchone()
    if not row: return None
    result = dict(row)
    result['waveform'] = json.loads(result['waveform'])
    return result

def cutoff(db, owner, peer):
    row = db.execute('SELECT through_id FROM conversation_clears WHERE owner_id=? AND peer_id=?', (owner, peer)).fetchone()
    return row[0] if row else 0

def sniff_mime(prefix: bytes) -> str:
    if prefix.startswith(b"\x89PNG\r\n\x1a\n"): return "image/png"
    if prefix.startswith(b"\xff\xd8\xff"): return "image/jpeg"
    if prefix.startswith((b"GIF87a", b"GIF89a")): return "image/gif"
    if len(prefix) >= 12 and prefix[:4] == b"RIFF" and prefix[8:12] == b"WEBP": return "image/webp"
    if prefix.startswith(b"BM"): return "image/bmp"
    if prefix.startswith(b"%PDF-"): return "application/pdf"
    if prefix.startswith(b"PK\x03\x04"): return "application/zip"
    if len(prefix) >= 12 and prefix[4:8] == b"ftyp":
        brand = prefix[8:12].lower()
        return "audio/mp4" if brand in (b"m4a ", b"m4b ", b"m4p ", b"f4a ") else "video/mp4"
    if prefix.startswith(b"\x1aE\xdf\xa3"): return "video/webm"
    if prefix.startswith((b"ID3", b"OggS", b"fLaC", b"RIFF")):
        if prefix.startswith(b"OggS"): return "audio/ogg"
        if prefix.startswith(b"fLaC"): return "audio/flac"
        if prefix.startswith(b"RIFF") and prefix[8:12] == b"WAVE": return "audio/wav"
        if prefix.startswith(b"ID3"): return "audio/mpeg"
    if len(prefix) >= 2 and prefix[0] == 0xFF and (prefix[1] & 0xF6) == 0xF0: return "audio/aac"
    if prefix and b"\x00" not in prefix:
        try:
            prefix.decode("utf-8-sig")
            return "text/plain"
        except UnicodeDecodeError:
            pass
    return "application/octet-stream"

def verified_mime(declared: str, prefix: bytes, filename: str) -> str:
    actual = sniff_mime(prefix)
    declared = {"image/jpg": "image/jpeg", "audio/x-m4a": "audio/mp4"}.get(declared, declared)
    text_types = {"application/json", "application/xml", "application/javascript", "application/x-yaml", "application/yaml", "application/csv"}
    office_ext = Path(filename).suffix.lower()
    office_zip = {
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    }
    if actual == "application/zip" and office_zip.get(office_ext) == declared:
        return declared
    if actual == "text/plain" and (declared.startswith("text/") or declared in text_types):
        return declared
    if declared in ("application/octet-stream", "binary/octet-stream"):
        return actual
    if actual == "application/octet-stream":
        raise HTTPException(400, "Содержимое файла не соответствует его типу")
    if declared != actual:
        raise HTTPException(400, "Содержимое файла не соответствует его типу")
    return actual

def install_routes(app, connect, current_profile, throttle):
    @app.post('/attachments')
    async def upload(request: Request, peer_id: str = Query(min_length=36, max_length=36), name: str = Query(min_length=1, max_length=180), kind: str = Query(default='file', pattern='^(file|voice)$'), duration_ms: int = Query(default=0, ge=0, le=300000), waveform: str = Query(default='', max_length=160), authorization: str | None = Header(default=None)):
        owner = current_profile(authorization)['id']
        throttle('upload-attachment:' + owner, limit=12)
        try: uuid.UUID(peer_id)
        except ValueError: raise HTTPException(400, 'Неверный получатель')
        if owner == peer_id: raise HTTPException(400, 'Используй Избранное для личных файлов')
        filename = name.replace('\\', '/').split('/')[-1]
        filename = ''.join(c for c in filename if c.isprintable()).strip()[:180] or 'Файл'
        mime = request.headers.get('content-type', 'application/octet-stream').split(';')[0].lower()
        if not re.fullmatch(r'[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+', mime): mime = 'application/octet-stream'
        try: peaks = [int(value) for value in waveform.split(',')] if waveform else []
        except ValueError: raise HTTPException(400, 'Неверная форма голосового сообщения')
        if len(peaks) > 32 or any(value < 0 or value > 100 for value in peaks): raise HTTPException(400, 'Неверная форма голосового сообщения')
        if kind == 'voice' and (mime not in ('audio/mp4', 'audio/aac', 'audio/x-m4a') or duration_ms < 1000 or not peaks):
            raise HTTPException(400, 'Голосовое сообщение должно длиться от 1 секунды до 5 минут')
        if kind == 'file': duration_ms, peaks = 0, []
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
            db.execute('INSERT INTO attachments(id,owner_id,peer_id,name,mime,size,created,ready,kind,duration_ms,waveform) VALUES(?,?,?,?,?,?,?,0,?,?,?)', (attachment_id, owner, peer_id, filename, mime, reserved, time.time(), kind, duration_ms, json.dumps(peaks)))
        path = ROOT / attachment_id
        complete = False
        first_chunk = bytearray()
        try:
            total = 0
            with path.open('xb') as output:
                path.chmod(0o600)
                async for chunk in request.stream():
                    total += len(chunk)
                    if total > reserved or total > MAX_FILE: raise HTTPException(413, 'Максимальный размер файла — 25 МБ')
                    if len(first_chunk) < 4096:
                        first_chunk.extend(chunk[:4096 - len(first_chunk)])
                    output.write(chunk)
            if total == 0: raise HTTPException(400, 'Файл пустой')
            mime = verified_mime(mime, bytes(first_chunk), filename)
            with connect() as db:
                db.execute('UPDATE attachments SET size=?,mime=?,ready=1 WHERE id=?', (total, mime, attachment_id))
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
