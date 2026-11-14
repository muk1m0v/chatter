"""Run on the VPS: tests public HTTPS across independent credentials; removes only its generated fixtures."""
import json
import secrets
import sqlite3
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = 'https://mukimov.site/salom/api/'
profiles = []

def request(path, token=None, body=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body else None, headers=headers, method='PUT' if body else 'GET')
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

try:
    suffix = secrets.token_hex(4)
    for name in ('alpha', 'beta'):
        item = {'id': str(uuid.uuid4()), 'token': secrets.token_urlsafe(32), 'username': 'qa' + name + '_' + suffix}
        profiles.append(item)
        status, public = request('profile', item['token'], {'device_id': item['id'], 'name': 'Directory QA ' + name, 'username': item['username']})
        assert status == 200, (status, public)
        assert 'phone' not in public and 'token_hash' not in public
    a, b = profiles
    for prefix in (a['username'], '@' + a['username'].upper(), a['username'][:5]):
        status, result = request('search?q=' + urllib.parse.quote(prefix), b['token'])
        assert status == 200 and any(p['id'] == a['id'] for p in result['profiles'])
    print('TWO_ACCOUNT_GLOBAL_SEARCH=PASS')
    status, _ = request('profile', b['token'], {'device_id': b['id'], 'name': 'Duplicate', 'username': a['username'].upper()})
    assert status == 409
    print('CASE_INSENSITIVE_UNIQUE_USERNAME=PASS')
    status, _ = request('profile', b['token'], {'device_id': a['id'], 'name': 'Hijack', 'username': 'qahijack_' + suffix})
    assert status == 403
    print('CROSS_DEVICE_PROFILE_WRITE_BLOCKED=PASS')
    status, _ = request('search?q=' + a['username'])
    assert status == 401
    print('UNAUTHENTICATED_LOOKUP_BLOCKED=PASS')
    new_username = 'qarenamed_' + suffix
    status, _ = request('profile', a['token'], {'device_id': a['id'], 'name': 'Directory QA alpha', 'username': new_username})
    assert status == 200
    status, old = request('search?q=' + a['username'], b['token'])
    status, new = request('search?q=' + new_username, b['token'])
    assert not any(p['id'] == a['id'] for p in old['profiles'])
    assert any(p['id'] == a['id'] for p in new['profiles'])
    print('REMOTE_RENAME_PROPAGATES=PASS')
finally:
    with sqlite3.connect('/var/lib/salom-api/directory.sqlite3') as db:
        for item in profiles: db.execute('DELETE FROM profiles WHERE id=?', (item['id'],))
    print('GENERATED_QA_PROFILES_REMOVED')
