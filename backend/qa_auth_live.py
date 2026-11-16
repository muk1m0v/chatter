"""HTTPS Google-only/session integration using temporary server-owned QA fixtures."""
import json
import secrets
import sqlite3
import urllib.error
import urllib.request
import uuid
from qa_fixtures import enroll

base = 'https://mukimov.site/salom/api/'
fixtures = []
def request(path, token=None, body=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base+path, data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method or ('POST' if body is not None else 'GET'))
    try:
        with urllib.request.urlopen(req, timeout=20) as response: return response.status, json.load(response)
    except urllib.error.HTTPError as error: return error.code, json.load(error)

try:
    for index in range(2):
        account = {'id': str(uuid.uuid4()), 'token': secrets.token_urlsafe(32), 'username': 'qagoogle'+str(index)+'_'+secrets.token_hex(4)}
        fixtures.append(account)
        enroll(account['id'], account['token'], 'Google QA', account['username'])
    a, b = fixtures
    config = request('auth/config')[1]
    assert config['google_enabled'] and not config['sms_enabled'] and not config['email_enabled']
    for route in ('login', 'password', 'email/start', 'email/confirm'):
        assert request('auth/'+route, body={})[0] == 404
    assert request('auth/identity', body={'firebase_token': 'invalid'})[0] == 401
    print('HTTPS_GOOGLE_ONLY_AND_INVALID_PROOF=PASS')
    assert request('auth/username?username='+a['username'])[1]['available'] is False
    assert request('auth/me', a['token'])[0] == 200
    status, sent = request('messages', a['token'], {'recipient_id': b['id'], 'client_id': str(uuid.uuid4()), 'text': 'QA Google session integration'})
    assert status == 200
    status, history = request('messages?peer_id='+b['id'], a['token'])
    assert status == 200 and history['messages'][0]['id'] == sent['id']
    assert 'email' not in request('search?q='+b['username'], a['token'])[1].get('profiles', [{}])[0]
    print('HTTPS_SESSION_MESSAGING_AND_PRIVATE_EMAIL=PASS')
    assert request('auth/logout', a['token'], {})[0] == 200
    assert request('auth/me', a['token'])[0] == 401
    assert request('auth/me', b['token'])[0] == 200
    print('HTTPS_LOGOUT_REVOKES_ONLY_CURRENT_SESSION=PASS')
    status, _ = request('profile', secrets.token_urlsafe(32), {'device_id': str(uuid.uuid4()), 'name': 'Bypass', 'username': 'qafake_'+secrets.token_hex(4)}, 'PUT')
    assert status == 426
    print('HTTPS_UNVERIFIED_ENROLLMENT_BLOCKED=PASS')
finally:
    with sqlite3.connect('/var/lib/salom-api/directory.sqlite3') as db:
        db.execute('PRAGMA foreign_keys=ON')
        for account in fixtures: db.execute('DELETE FROM profiles WHERE id=? AND username=?', (account['id'], account['username']))
    print('AUTH_QA_FIXTURES_REMOVED')
