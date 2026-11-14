"""A temporary QA peer; credential stays only in this process memory."""
import json
import os
from pathlib import Path
import secrets
import sqlite3
import sys
import time
import urllib.request
import uuid

BASE = 'https://mukimov.site/salom/api/'
record = Path('/tmp/salom-messaging-ui-probe.json')
database = '/var/lib/salom-api/directory.sqlite3'

def cleanup(data):
    assert data['username'].startswith('qachat_')
    with sqlite3.connect(database) as db:
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('DELETE FROM profiles WHERE id=? AND username=?', (data['id'], data['username']))
    if record.exists(): record.unlink()

if sys.argv[1] == 'cleanup':
    cleanup(json.loads(record.read_text()))
    print('QA_PEER_AND_ITS_MESSAGES_REMOVED')
else:
    assert not record.exists(), 'Clean up the previous probe first'
    data = {'id': str(uuid.uuid4()), 'username': 'qachat_' + secrets.token_hex(4)}
    token = secrets.token_urlsafe(32)
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    def request(path, body=None, method=None):
        req = urllib.request.Request(BASE + path, data=json.dumps(body, ensure_ascii=False).encode() if body else None, headers=headers, method=method or ('PUT' if body else 'GET'))
        with urllib.request.urlopen(req, timeout=20) as response: return json.load(response)
    success = False
    try:
        request('profile', {'device_id': data['id'], 'name': 'QA переписки', 'username': data['username']})
        target = sys.argv[2].removeprefix('@')
        owner = next(profile for profile in request('search?q=' + target)['profiles'] if profile['username'].lower() == target.lower())
        descriptor = os.open(record, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'w') as output: json.dump(data, output)
        print(json.dumps({'username': data['username'], 'ready': True}), flush=True)
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            incoming = request('messages?peer_id=' + owner['id'])['messages']
            if any(message['sender_id'] == owner['id'] and message['text'] == 'Salom050check' for message in incoming):
                request('messages', {'recipient_id': owner['id'], 'client_id': str(uuid.uuid4()), 'text': 'Салом! Сообщение получено 💛'}, 'POST')
                print('ANDROID_SEND_RECEIVED_AND_SERVER_REPLY_SENT=PASS', flush=True)
                success = True
                break
            time.sleep(2)
        assert success, 'Timed out waiting for the exact QA message'
    finally:
        if not success: cleanup(data)
