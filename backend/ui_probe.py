"""Temporary synthetic second account for Android UI QA; never exposes its credential."""
import json
import os
from pathlib import Path
import secrets
import sqlite3
import sys
import urllib.parse
import urllib.request
import uuid
from qa_fixtures import enroll

record = Path('/tmp/salom-directory-ui-probe.json')
database = '/var/lib/salom-api/directory.sqlite3'
if sys.argv[1] == 'cleanup':
    data = json.loads(record.read_text())
    assert data['username'].startswith('qaremote_')
    with sqlite3.connect(database) as db:
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('DELETE FROM profiles WHERE id=? AND username=?', (data['id'], data['username']))
    record.unlink()
    print('UI_PROBE_REMOVED')
else:
    assert not record.exists(), 'Clean up the previous UI probe first'
    data = {'id': str(uuid.uuid4()), 'username': 'qaremote_' + secrets.token_hex(4)}
    token = secrets.token_urlsafe(32)
    enroll(data['id'], token, 'QA каталога (проверка)', data['username'])
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    body = {'device_id': data['id'], 'name': 'QA каталога (проверка)', 'username': data['username']}
    try:
        request = urllib.request.Request('https://mukimov.site/salom/api/profile', data=json.dumps(body).encode(), headers=headers, method='PUT')
        with urllib.request.urlopen(request, timeout=20) as response: assert response.status == 200
        target = sys.argv[2]
        request = urllib.request.Request('https://mukimov.site/salom/api/search?q=' + urllib.parse.quote(target), headers=headers)
        with urllib.request.urlopen(request, timeout=20) as response: result = json.load(response)
        assert any(profile['username'].lower() == target.removeprefix('@').lower() for profile in result['profiles']), 'Target profile is not publicly synchronized'
        descriptor = os.open(record, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'w') as output: json.dump(data, output)
        print(json.dumps({'username': data['username'], 'target_visible_from_second_account': True}))
    except Exception:
        with sqlite3.connect(database) as db:
            db.execute('PRAGMA foreign_keys=ON')
            db.execute('DELETE FROM profiles WHERE id=?', (data['id'],))
        raise
