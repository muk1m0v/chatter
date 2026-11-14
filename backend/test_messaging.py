"""HTTPS integration tests using only self-created synthetic accounts."""
import json
import secrets
import sqlite3
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = 'https://mukimov.site/salom/api/'
accounts = []
def request(path, token=None, body=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body else None, headers=headers, method=method or ('PUT' if body else 'GET'))
    try:
        with urllib.request.urlopen(req, timeout=20) as response: return response.status, json.load(response)
    except urllib.error.HTTPError as error: return error.code, json.load(error)

try:
    suffix = secrets.token_hex(4)
    for index in range(3):
        account = {'id': str(uuid.uuid4()), 'token': secrets.token_urlsafe(32), 'username': f'qam{index}_{suffix}'}
        accounts.append(account)
        status, result = request('profile', account['token'], {'device_id': account['id'], 'name': 'Messaging QA', 'username': account['username']})
        assert status == 200, result
    a, b, outsider = accounts
    message = {'recipient_id': b['id'], 'client_id': str(uuid.uuid4()), 'text': 'Салом 💛 — проверка доставки'}
    status, sent = request('messages', a['token'], message, 'POST')
    assert status == 200 and sent['sender_id'] == a['id']
    status, duplicate = request('messages', a['token'], message, 'POST')
    assert status == 200 and duplicate['id'] == sent['id']
    print('IDEMPOTENT_SEND=PASS')
    status, received = request('messages?peer_id=' + a['id'], b['token'])
    assert status == 200 and received['messages'][0]['text'] == message['text']
    print('CROSS_ACCOUNT_MESSAGE_DELIVERY=PASS')
    reply = {'recipient_id': a['id'], 'client_id': str(uuid.uuid4()), 'text': 'Ответ получен'}
    status, replied = request('messages', b['token'], reply, 'POST')
    status, incoming = request('messages?peer_id=' + b['id'] + '&after=' + str(sent['id']), a['token'])
    assert status == 200 and len(incoming['messages']) == 1 and incoming['messages'][0]['id'] == replied['id']
    print('REPLY_AND_INCREMENTAL_FETCH=PASS')
    status, private = request('messages?peer_id=' + a['id'], outsider['token'])
    assert status == 200 and private['messages'] == []
    status, private_inbox = request('conversations', outsider['token'])
    assert private_inbox['conversations'] == []
    print('THIRD_ACCOUNT_CANNOT_READ_CONVERSATION=PASS')
    status, inbox = request('conversations', b['token'])
    assert status == 200 and inbox['conversations'][0]['profile']['id'] == a['id']
    print('RECIPIENT_INBOX=PASS')
    status, _ = request('messages', None, message, 'POST')
    assert status == 401
    altered = dict(message, text='different payload')
    status, _ = request('messages', a['token'], altered, 'POST')
    assert status == 409
    print('AUTH_AND_DUPLICATE_PAYLOAD_PROTECTION=PASS')
finally:
    with sqlite3.connect('/var/lib/salom-api/directory.sqlite3') as db:
        db.execute('PRAGMA foreign_keys=ON')
        for account in accounts: db.execute('DELETE FROM profiles WHERE id=?', (account['id'],))
    print('SYNTHETIC_ACCOUNTS_AND_MESSAGES_REMOVED')
