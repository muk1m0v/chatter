"""Controlled media peer for device QA. Never uses any real friend's account."""
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

BASE='https://mukimov.site/salom/api/'
record=Path('/tmp/salom-media-ui-probe.json')
folder=Path('/tmp/salom-api-deploy')
def request(data,path,body=None,mime='application/json',method=None,binary=False):
    payload=json.dumps(body).encode() if isinstance(body,dict) else body
    req=urllib.request.Request(BASE+path,data=payload,headers={'Authorization':'Bearer '+data['token'],'Content-Type':mime},method=method or ('POST' if body is not None else 'GET'))
    with urllib.request.urlopen(req,timeout=40) as response:
        result=response.read(); return result if binary else json.loads(result)
def cleanup(data):
    assert data['username'].startswith('qaui_media_')
    with sqlite3.connect('/var/lib/salom-api/directory.sqlite3') as db:
        db.execute('PRAGMA foreign_keys=ON')
        rows=db.execute('SELECT id FROM attachments WHERE owner_id=? OR peer_id=?',(data['id'],data['id'])).fetchall()
        for row in rows:
            uuid.UUID(row[0]); (Path('/var/lib/salom-api/attachments')/row[0]).unlink(missing_ok=True)
        db.execute('DELETE FROM messages WHERE sender_id=? OR recipient_id=?',(data['id'],data['id']))
        db.execute('DELETE FROM profiles WHERE id=? AND username=?',(data['id'],data['username']))
    record.unlink(missing_ok=True)
mode=sys.argv[1]
if mode=='seed':
    assert not record.exists(),'Previous QA probe must be cleaned first'
    data={'id':str(uuid.uuid4()),'token':secrets.token_urlsafe(32),'username':'qaui_media_'+secrets.token_hex(3)}
    with sqlite3.connect('/var/lib/salom-api/directory.sqlite3') as db:
        data['owner']=db.execute('SELECT id FROM profiles WHERE username_key=?',('muk1mov',)).fetchone()[0]
    enroll(data['id'],data['token'],'Проверка вложений',data['username'])
    descriptor=os.open(record,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(descriptor,'w') as stream: json.dump(data,stream)
    try:
        for name,mime in [('salom-media-qa.png','image/png'),('salom-media-qa.txt','text/plain'),('salom-media-qa.mp4','video/mp4')]:
            contents=(folder/name).read_bytes()
            query=urllib.parse.urlencode({'peer_id':data['owner'],'name':name})
            attachment=request(data,'attachments?'+query,contents,mime=mime)
            request(data,'messages',{'recipient_id':data['owner'],'client_id':str(uuid.uuid4()),'text':'','attachment_id':attachment['id']})
        print(json.dumps({'username':data['username'],'ready':True}))
    except Exception: cleanup(data); raise
else:
    data=json.loads(record.read_text())
    if mode=='cleanup': cleanup(data); print('MEDIA_UI_FIXTURE_AND_FILES_REMOVED')
    elif mode=='verify-send':
        history=request(data,'messages?peer_id='+data['owner'])['messages']
        message=next(m for m in history if m['sender_id']==data['owner'] and m['text']=='Media010check' and m['attachment'])
        actual=request(data,'attachments/'+message['attachment']['id'],binary=True)
        assert actual==(folder/'salom-media-qa.png').read_bytes()
        print('ANDROID_ATTACHMENT_UPLOAD_AND_CAPTION_RECEIVED_BY_OTHER_ACCOUNT=PASS')
    elif mode=='verify-clear':
        with sqlite3.connect('/var/lib/salom-api/directory.sqlite3') as db:
            cutoff=db.execute('SELECT through_id FROM conversation_clears WHERE owner_id=? AND peer_id=?',(data['owner'],data['id'])).fetchone()[0]
            count=db.execute('SELECT COUNT(*) FROM messages WHERE id<=? AND ((sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?))',(cutoff,data['owner'],data['id'],data['id'],data['owner'])).fetchone()[0]
        assert count>=3
        assert len(request(data,'messages?peer_id='+data['owner'])['messages'])>=3
        print('ANDROID_CLEAR_STORED_WITH_OTHER_PARTICIPANT_HISTORY_RETAINED=PASS')
    else: raise ValueError('Unsupported QA mode')
