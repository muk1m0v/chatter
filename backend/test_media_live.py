"""HTTPS media smoke test. Temporary QA participants and attachment files only."""
import base64
import hashlib
import json
from pathlib import Path
import secrets
import sqlite3
import urllib.error
import urllib.parse
import urllib.request
import uuid
from qa_fixtures import enroll

BASE='https://mukimov.site/salom/api/'
accounts=[]
attachments=[]
def call(path,index=0,body=None,method=None,mime='application/json',binary=False):
    headers={'Authorization':'Bearer '+accounts[index]['token'],'Content-Type':mime}
    data=json.dumps(body).encode() if isinstance(body,dict) else body
    req=urllib.request.Request(BASE+path,data=data,headers=headers,method=method or ('POST' if body is not None else 'GET'))
    try:
        with urllib.request.urlopen(req,timeout=40) as r:
            payload=r.read()
            return r.status, payload if binary else json.loads(payload)
    except urllib.error.HTTPError as e: return e.code, e.read()
try:
    for i in range(3):
        account={'id':str(uuid.uuid4()),'token':secrets.token_urlsafe(32),'username':'qamedia'+str(i)+'_'+secrets.token_hex(4)}
        accounts.append(account); enroll(account['id'],account['token'],'Media QA',account['username'])
    samples=[('photo.png','image/png',base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB1sAAAAASUVORK5CYII=')),('large-file.txt','text/plain',b'Salom QA attachment\n'*32000)]
    last=0
    for name,mime,data in samples:
        query=urllib.parse.urlencode({'peer_id':accounts[1]['id'],'name':name})
        status,attachment=call('attachments?'+query,body=data,mime=mime)
        assert status==200,(status,attachment)
        attachments.append(attachment['id'])
        key=str(uuid.uuid4())
        body={'recipient_id':accounts[1]['id'],'client_id':key,'attachment_id':attachment['id'],'text':''}
        status,sent=call('messages',body=body); assert status==200
        assert call('messages',body=body)[1]['id']==sent['id']
        last=sent['id']
        status,received=call('attachments/'+attachment['id'],index=1,binary=True)
        assert status==200 and hashlib.sha256(received).digest()==hashlib.sha256(data).digest()
        assert call('attachments/'+attachment['id'],index=2,binary=True)[0]==404
    print('HTTPS_PHOTO_AND_LARGE_FILE_ROUND_TRIP_AND_PRIVATE_ACCESS=PASS')
    # Synthetic transport fixture, not a decoded microphone recording.
    voice_bytes = b'Salom synthetic voice transport fixture'
    voice_query = urllib.parse.urlencode({'peer_id':accounts[1]['id'],'name':'voice-qa.m4a','kind':'voice','duration_ms':2400,'waveform':'0,12,55,100,22,0'})
    status,voice = call('attachments?'+voice_query,body=voice_bytes,mime='audio/mp4')
    assert status==200,(status,voice)
    attachments.append(voice['id'])
    status,sent=call('messages',body={'recipient_id':accounts[1]['id'],'client_id':str(uuid.uuid4()),'attachment_id':voice['id'],'text':''})
    assert status==200
    last=sent['id']
    received=call('messages?peer_id='+accounts[0]['id'],index=1)[1]['messages'][-1]['attachment']
    assert received['kind']=='voice' and received['duration_ms']==2400 and received['waveform']==[0,12,55,100,22,0]
    assert call('attachments/'+voice['id'],index=1,binary=True)[1]==voice_bytes
    assert call('attachments/'+voice['id'],index=2,binary=True)[0]==404
    assert call('conversations',index=1)[1]['conversations'][0]['last_text']=='Голосовое сообщение'
    print('HTTPS_VOICE_METADATA_BYTES_PRIVATE_ACCESS_AND_CHAT_PREVIEW=PASS')

    peer=accounts[1]['id']
    status,cleared=call('messages?peer_id='+peer,method='DELETE'); assert status==200 and cleared['cleared_through']==last
    assert call('messages?peer_id='+peer)[1]['messages']==[]
    assert len(call('messages?peer_id='+accounts[0]['id'],index=1)[1]['messages'])==3
    assert call('attachments/'+attachments[0],binary=True)[0]==404
    assert call('attachments/'+attachments[0],index=1,binary=True)[0]==200
    body={'recipient_id':accounts[0]['id'],'client_id':str(uuid.uuid4()),'text':'QA after clear'}
    assert call('messages',index=1,body=body)[0]==200
    assert len(call('messages?peer_id='+peer)[1]['messages'])==1
    print('HTTPS_CLEAR_PERSISTS_OTHER_PARTICIPANT_KEEPS_HISTORY_AND_NEW_MESSAGES_WORK=PASS')
finally:
    with sqlite3.connect('/var/lib/salom-api/directory.sqlite3') as db:
        db.execute('PRAGMA foreign_keys=ON')
        for account in accounts:
            rows=db.execute('SELECT id FROM attachments WHERE owner_id=? OR peer_id=?',(account['id'],account['id'])).fetchall()
            for row in rows:
                uuid.UUID(row[0]); (Path('/var/lib/salom-api/attachments')/row[0]).unlink(missing_ok=True)
            db.execute('DELETE FROM messages WHERE sender_id=? OR recipient_id=?',(account['id'],account['id']))
            db.execute('DELETE FROM profiles WHERE id=? AND username=?',(account['id'],account['username']))
    print('MEDIA_QA_PROFILES_MESSAGES_AND_FILES_REMOVED')
