"""Replace only the landing page, keeping API, APKs and Nginx configuration intact."""
import datetime
import hashlib
import os
from pathlib import Path
import shutil

source = Path('/tmp/salom-page-deploy/index.html')
target = Path('/var/www/mukimov-test/index.html')
html = source.read_text(encoding='utf-8')
assert '<title>Салом — скачать для Android</title>' in html
assert 'id="hero-download"' in html
assert html.count('href="/salom/salom-latest.apk"') == 4
assert target.is_file()
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup = Path('/var/backups') / ('salom-page-' + stamp)
backup.mkdir(mode=0o700)
shutil.copy2(target, backup / 'index.html')
staged = target.parent / '.salom-index-next.html'
staged.write_text(html, encoding='utf-8')
staged.chmod(0o644)
os.replace(staged, target)
print('PAGE_SHA256=' + hashlib.sha256(target.read_bytes()).hexdigest())
print('BACKUP=' + str(backup))
