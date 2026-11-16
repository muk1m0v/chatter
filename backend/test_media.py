"""Private attachment and per-owner clear tests, using isolated DB/files only."""
import hashlib
from pathlib import Path
import secrets
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid
from fastapi.testclient import TestClient
import app
import media

class MediaTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old_db, self.old_root = app.DB, media.ROOT
        app.DB = Path(self.tmp.name) / 'test.sqlite3'
        media.ROOT = Path(self.tmp.name) / 'files'
        self.push = patch('app.PushDispatcher'); self.push.start()
        self.client = TestClient(app.app); self.client.__enter__()
        self.ids, self.tokens = [str(uuid.uuid4()) for _ in range(3)], [secrets.token_urlsafe(32) for _ in range(3)]
        with app.connect() as db:
            for index in range(3):
                db.execute('INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)', (self.ids[index], hashlib.sha256(self.tokens[index].encode()).hexdigest(), 'Media QA', 'qa'+str(index), 'qa'+str(index), time.time(), time.time()))
    def tearDown(self):
        self.client.__exit__(None, None, None); self.push.stop()
        app.DB, media.ROOT = self.old_db, self.old_root
        self.tmp.cleanup()
    def headers(self, index=0): return {'Authorization': 'Bearer '+self.tokens[index]}
    def upload(self, data=b'QA sample bytes', index=0, peer=1, name='sample.txt', mime='text/plain'):
        return self.client.post('/attachments', params={'peer_id': self.ids[peer], 'name': name}, headers={**self.headers(index), 'Content-Type': mime}, content=data)
    def send(self, attachment=None, sender=0, peer=1, text='', client_id=None):
        body={'recipient_id':self.ids[peer], 'client_id':client_id or str(uuid.uuid4()), 'text':text}
        if attachment: body['attachment_id']=attachment['id']
        return self.client.post('/messages', json=body, headers=self.headers(sender))
    def history(self, owner=0, peer=1): return self.client.get('/messages', params={'peer_id':self.ids[peer]}, headers=self.headers(owner)).json()
    def download(self, attachment, owner): return self.client.get('/attachments/'+attachment['id'], headers=self.headers(owner))
    def clear(self, owner=0, peer=1): return self.client.delete('/messages', params={'peer_id':self.ids[peer]}, headers=self.headers(owner))

    def test_private_upload_download_and_idempotent_attachment_send(self):
        attachment=self.upload().json()
        self.assertEqual(self.download(attachment,1).status_code,404)
        self.assertEqual(self.download(attachment,2).status_code,404)
        self.assertEqual(self.client.get('/attachments/'+attachment['id']).status_code,401)
        key=str(uuid.uuid4())
        sent=self.send(attachment,client_id=key)
        self.assertEqual(sent.status_code,200,sent.text)
        self.assertEqual(self.send(attachment,client_id=key).json()['id'],sent.json()['id'])
        self.assertEqual(self.download(attachment,1).content,b'QA sample bytes')
        self.assertEqual(self.history(1,0)['messages'][0]['attachment'],attachment)
        self.assertEqual(self.send(attachment).status_code,409)
        self.assertEqual(self.send(attachment,client_id=key,text='different').status_code,409)

    def test_wrong_owner_or_recipient_cannot_send_uploaded_file(self):
        attachment=self.upload().json()
        self.assertEqual(self.send(attachment,sender=2,peer=1).status_code,400)
        self.assertEqual(self.send(attachment,peer=2).status_code,400)
        self.assertEqual(self.send().status_code,400)

    def test_clear_is_persistent_private_and_allows_future_messages(self):
        attachment=self.upload().json(); old=self.send(attachment).json()
        cleared=self.clear().json()['cleared_through']
        self.assertEqual(cleared,old['id'])
        self.assertEqual(self.history()['messages'],[])
        self.assertEqual(self.history()['cleared_through'],cleared)
        self.assertEqual(len(self.history(1,0)['messages']),1)
        self.assertEqual(self.client.get('/conversations',headers=self.headers()).json()['conversations'],[])
        self.assertEqual(self.download(attachment,0).status_code,404)
        self.assertEqual(self.download(attachment,1).status_code,200)
        new=self.send(sender=1,peer=0,text='New message').json()
        self.assertEqual([m['id'] for m in self.history()['messages']],[new['id']])
        self.assertEqual(len(self.client.get('/conversations',headers=self.headers()).json()['conversations']),1)
        self.clear(owner=2,peer=1)
        self.assertEqual(len(self.history(1,0)['messages']),2)

    def test_upload_size_and_account_quota(self):
        with patch('media.MAX_FILE',8):
            self.assertEqual(self.upload(b'123456789').status_code,413)
        with patch('media.MAX_ACCOUNT',6):
            self.assertEqual(self.upload(b'1234').status_code,200)
            self.assertEqual(self.upload(b'567').status_code,413)
        with app.connect() as db: self.assertEqual(db.execute('SELECT SUM(size) FROM attachments').fetchone()[0],4)

    def test_empty_upload_does_not_leave_record_or_file(self):
        self.assertEqual(self.upload(b'').status_code,413)
        with app.connect() as db: self.assertEqual(db.execute('SELECT COUNT(*) FROM attachments').fetchone()[0],0)

    def test_filename_is_sanitized_and_download_is_nosniff(self):
        attachment=self.upload(name='../folder/qa.txt',mime='text/html').json()
        self.assertEqual(attachment['name'],'qa.txt')
        self.send(attachment)
        downloaded=self.download(attachment,1)
        self.assertEqual(downloaded.headers['x-content-type-options'],'nosniff')
        self.assertIn('attachment;',downloaded.headers['content-disposition'])

    def test_stale_unused_upload_is_cleaned_but_sent_file_survives(self):
        stale=self.upload().json(); sent=self.upload().json(); self.send(sent)
        with app.connect() as db: db.execute('UPDATE attachments SET created=?',(time.time()-90000,))
        self.assertEqual(self.upload().status_code,200)
        self.assertFalse((media.ROOT/stale['id']).exists())
        self.assertEqual(self.download(sent,1).status_code,200)

    def test_invalid_peer_and_attachment_are_rejected(self):
        response=self.client.post('/attachments',params={'peer_id':str(uuid.uuid4()),'name':'qa'},content=b'x',headers=self.headers())
        self.assertEqual(response.status_code,404)
        self.assertEqual(self.client.delete('/messages',params={'peer_id':str(uuid.uuid4())},headers=self.headers()).status_code,404)
        self.assertEqual(self.send({'id':str(uuid.uuid4())}).status_code,400)

    def test_voice_metadata_round_trip_and_private_access(self):
        params={'peer_id':self.ids[1], 'name':'voice_qa.m4a', 'kind':'voice', 'duration_ms':1234, 'waveform':'0,12,100,3'}
        result=self.client.post('/attachments',params=params,headers={**self.headers(),'Content-Type':'audio/mp4'},content=b'Voice transport QA')
        self.assertEqual(result.status_code,200,result.text)
        attachment=result.json()
        self.assertEqual((attachment['kind'],attachment['duration_ms'],attachment['waveform']),('voice',1234,[0,12,100,3]))
        self.assertEqual(self.send(attachment).status_code,200)
        received=self.history(1,0)['messages'][0]['attachment']
        self.assertEqual(received,attachment)
        self.assertEqual(self.client.get('/conversations',headers=self.headers(1)).json()['conversations'][0]['last_text'],'Голосовое сообщение')
        self.assertEqual(self.download(attachment,1).content,b'Voice transport QA')
        self.assertEqual(self.download(attachment,2).status_code,404)

    def test_invalid_voice_metadata_is_rejected(self):
        base={'peer_id':self.ids[1], 'name':'voice.m4a', 'kind':'voice','duration_ms':1000,'waveform':'1,20,30'}
        for change in ({'duration_ms':0},{'duration_ms':999},{'duration_ms':300001},{'waveform':'-1,2'},{'waveform':'101'},{'waveform':'x'},{'waveform':','.join(['1']*33)},{'waveform':''}):
            result=self.client.post('/attachments',params={**base,**change},headers={**self.headers(),'Content-Type':'audio/mp4'},content=b'qa')
            self.assertIn(result.status_code,(400,422),change)
        result=self.client.post('/attachments',params=base,headers={**self.headers(),'Content-Type':'image/png'},content=b'qa')
        self.assertEqual(result.status_code,400)
        with app.connect() as db: self.assertEqual(db.execute('SELECT COUNT(*) FROM attachments').fetchone()[0],0)

    def test_existing_files_keep_default_kind_and_schema_is_repeatable(self):
        attachment=self.upload().json()
        self.assertEqual((attachment['kind'],attachment['duration_ms'],attachment['waveform']),('file',0,[]))
        app.initialize()
        self.assertEqual(self.send(attachment).status_code,200)
        self.assertEqual(self.history()['messages'][0]['attachment'],attachment)

if __name__=='__main__': unittest.main()
