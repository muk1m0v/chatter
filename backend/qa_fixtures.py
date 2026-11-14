"""Server-side QA fixtures only; never expose this helper through an API route."""
import hashlib
import sqlite3
import secrets
import time
from uuid import UUID

def enroll(profile_id, token, name, username):
    UUID(profile_id)
    assert username.startswith('qa') and len(token) >= 43
    now = time.time()
    with sqlite3.connect('/var/lib/salom-api/directory.sqlite3') as db:
        db.execute('PRAGMA foreign_keys=ON')
        # The fixture authenticates through a temporary session, as the new Android app does.
        db.execute('INSERT INTO profiles VALUES(?,?,?,?,?,1,?,?)', (profile_id, hashlib.sha256(secrets.token_bytes(32)).hexdigest(), name, username, username.lower(), now, now))
        db.execute('INSERT INTO accounts(profile_id,email,created) VALUES(?,?,?)', (profile_id, profile_id+'@qa.example.invalid', now))
        db.execute('INSERT INTO sessions VALUES(?,?,?,?,?)', (hashlib.sha256(token.encode()).hexdigest(), profile_id, now, now+3600, now))
