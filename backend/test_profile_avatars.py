import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
import app
import media


class ProfileAvatarTests(unittest.TestCase):
    def test_upload_replaces_avatar_and_updates_public_profile(self):
        tmp = self.enterContext(tempfile.TemporaryDirectory())
        self.enterContext(patch.multiple(app, DB=Path(tmp) / 'avatars.sqlite3', PG_DSN=None))
        app.initialize()
        owner = str(uuid4())
        with app.connect() as db:
            db.execute('INSERT INTO profiles(id,token_hash,name,created,updated) VALUES(?,?,?,?,?)',
                       (owner, 'fixture', 'Test', 1, 1))

        def authenticate(authorization):
            if authorization != 'Bearer fixture':
                raise HTTPException(401, 'Login required')
            return {'id': owner}

        api = FastAPI()
        media.install_routes(api, app.connect, authenticate, lambda *args, **kwargs: None)
        client = TestClient(api)
        path = '/profiles/me/avatar'
        jpeg = b'\xff\xd8\xfffixture-image\xff\xd9'
        self.assertEqual(client.put(path, content=jpeg).status_code, 401)
        headers = {'Authorization': 'Bearer fixture'}
        self.assertEqual(client.put(path, content=b'not an image', headers=headers).status_code, 400)
        original = client.put(path, content=jpeg, headers=headers).json()['avatar_url']
        old_path = original.split('/salom/api', 1)[1]
        self.assertEqual(client.get(old_path).content, jpeg)
        updated = client.put(path, content=jpeg.replace(b'image', b'photo'), headers=headers).json()['avatar_url']
        self.assertEqual(client.get(old_path).status_code, 404)
        with app.connect() as db:
            self.assertEqual(db.execute('SELECT avatar_url FROM profiles WHERE id=?', (owner,)).fetchone()['avatar_url'], updated)
