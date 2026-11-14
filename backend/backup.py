"""Consistent private SQLite snapshot; run as root on the VPS."""
import datetime
from pathlib import Path
import sqlite3

source = Path('/var/lib/salom-api/directory.sqlite3')
assert source.is_file()
folder = Path('/var/backups/salom-directory')
folder.mkdir(mode=0o700, exist_ok=True)
folder.chmod(0o700)
target = folder / ('directory-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '.sqlite3')
assert not target.exists()
with sqlite3.connect(source) as origin, sqlite3.connect(target) as backup:
    origin.backup(backup)
target.chmod(0o600)
print('BACKUP=' + str(target))
