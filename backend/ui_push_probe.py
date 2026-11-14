"""Controlled QA sender for Android push tests. Never messages a real friend."""
import json
import os
from pathlib import Path
import secrets
import sqlite3
import sys
import urllib.request
import uuid
from qa_fixtures import enroll

record = Path('/tmp/salom-push-ui-probe.json')
base = 'https://mukimov.site/salom/api/'
def request(path, token, body=None, method=None):
    req = urllib.request.Request(base + path, data=json.dumps(body, ensure_ascii=False).encode() if body else None,
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}, method=method or ('PUT' if body else 'GET'))
    with urllib.request.urlopen(req, timeout=20) as response: return json.load(response)

if sys.argv[1] == 'create':
    assert not record.exists()
    data = {'id': str(uuid.uuid4()), 'token': secrets.token_urlsafe(32), 'username': 'qapush_' + secrets.token_hex(4)}
    enroll(data['id'], data['token'], 'Проверка уведомлений', data['username'])
    request('profile', data['token'], {'device_id': data['id'], 'name': 'Проверка уведомлений', 'username': data['username']})
    target = sys.argv[2].removeprefix('@')
    owner = next(p for p in request('search?q=' + target, data['token'])['profiles'] if p['username'].lower() == target.lower())
    data['recipient'] = owner['id']
    descriptor = os.open(record, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, 'w') as output: json.dump(data, output)
    print('PUSH_QA_SENDER_READY username=' + data['username'])
elif sys.argv[1] == 'send':
    data = json.loads(record.read_text())
    sent = request('messages', data['token'], {'recipient_id': data['recipient'], 'client_id': str(uuid.uuid4()),
        'text': 'Уведомления Салом работают 💛'}, 'POST')
    print('QA_MESSAGE_SENT id=' + str(sent['id']))
else:
    assert sys.argv[1] == 'cleanup'
    data = json.loads(record.read_text())
    assert data['username'].startswith('qapush_')
    with sqlite3.connect('/var/lib/salom-api/directory.sqlite3') as db:
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('DELETE FROM profiles WHERE id=? AND username=?', (data['id'], data['username']))
    record.unlink()
    print('PUSH_QA_SENDER_MESSAGES_AND_JOBS_REMOVED')
