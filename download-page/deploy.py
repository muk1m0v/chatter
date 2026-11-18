"""Run on the authorized VPS after uploading index.html and release files to /tmp/salom-deploy.
Preserves existing proxy routes and backs up the current site/config before publishing.
"""
from pathlib import Path
import datetime
import hashlib
import json
import re
import shutil
import subprocess

incoming = Path('/tmp/salom-deploy')
release_input = json.loads((incoming / 'release.json').read_text(encoding='utf-8'))
version = release_input.get('version', '')
display_name = release_input.get('displayName', '')
version_code = release_input.get('versionCode')
min_sdk = release_input.get('minSdk', 24)
release_notes = release_input.get('releaseNotes', [])
assert isinstance(version, str) and re.fullmatch(r'\d+\.\d+\.\d+', version)
assert isinstance(display_name, str) and len(display_name) < 24 and re.fullmatch(r'[A-Za-z0-9 -]*', display_name)
assert isinstance(version_code, int) and version_code > 0
assert isinstance(min_sdk, int) and 24 <= min_sdk <= 100
assert isinstance(release_notes, list) and len(release_notes) <= 5 and all(isinstance(note, str) and len(note) <= 240 for note in release_notes)
apk = incoming / f'salom-{version}.apk'
assert apk.is_file() and apk.stat().st_size > 100_000
windows_file = release_input.get('windowsFile')
windows_size = None
windows_sha256 = None
if windows_file:
    assert isinstance(windows_file, str) and re.fullmatch(r'SALOM-[0-9.]+-AllowCookie-Setup\.exe', windows_file)
    windows_installer = incoming / windows_file
    assert windows_installer.is_file() and windows_installer.stat().st_size > 1_000_000
    windows_size = windows_installer.stat().st_size
    windows_sha256 = hashlib.sha256(windows_installer.read_bytes()).hexdigest()
assert (incoming / 'index.html').is_file()
config = Path('/etc/nginx/sites-available/salom-site.conf')
page = Path('/var/www/salom-site/index.html')
assert 'server_name mukimov.site;' in config.read_text()
assert 'alias /var/www/salom/;' in config.read_text(), 'Nginx must already serve /salom/ from /var/www/salom/'
assert 'alias /var/www/salom/version.json;' in config.read_text(), 'Nginx must already serve /salom/version.json'
backup = Path('/var/backups') / ('salom-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
backup.mkdir(parents=True, exist_ok=False)
shutil.copy2(config, backup / 'nginx.conf')
shutil.copy2(page, backup / 'index.html')
target = Path('/var/www/salom')
target.mkdir(parents=True, exist_ok=True)
for filename in ('salom-latest.apk', 'version.json'):
    if (target / filename).exists(): shutil.copy2(target / filename, backup / filename)
if windows_file and (target / windows_file).exists():
    shutil.copy2(target / windows_file, backup / windows_file)
sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
old_metadata_path = target / 'version.json'
if old_metadata_path.is_file():
    old_metadata = json.loads(old_metadata_path.read_text(encoding='utf-8'))
    old_code = old_metadata.get('versionCode', 0)
    assert version_code >= old_code, 'Published versionCode must never go backwards'
    if version_code == old_code:
        assert version == old_metadata.get('version') and sha256 == old_metadata.get('sha256'), 'A published build is immutable; use a higher versionCode for any new APK'
if (target / apk.name).exists():
    assert hashlib.sha256((target / apk.name).read_bytes()).hexdigest() == sha256, 'A published version must never change bytes'
for filename in (apk.name, 'salom-latest.apk'):
    temporary = target / (filename + '.tmp')
    shutil.copy2(apk, temporary)
    temporary.chmod(0o644)
    temporary.replace(target / filename)
if windows_file:
    if (target / windows_file).exists():
        assert hashlib.sha256((target / windows_file).read_bytes()).hexdigest() == windows_sha256, 'A published Windows installer must never change bytes'
    temporary = target / (windows_file + '.tmp')
    shutil.copy2(windows_installer, temporary)
    temporary.chmod(0o644)
    temporary.replace(target / windows_file)
metadata = {'version': version, 'displayName': display_name, 'versionCode': version_code, 'minSdk': min_sdk, 'downloadUrl': 'https://mukimov.site/salom/' + apk.name, 'size': apk.stat().st_size, 'sha256': sha256,
            'releaseNotes': release_notes}
if windows_file:
    metadata.update({'windowsFile': windows_file, 'windowsSize': windows_size, 'windowsSha256': windows_sha256})
for file in target.iterdir():
    if file.is_file(): file.chmod(0o644)
try:
    subprocess.run(['nginx', '-t'], check=True)
    shutil.copy2(incoming / 'index.html', page)
    page.chmod(0o644)
    subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
    # Advertise the release only after its immutable APK URL is live.
    temporary = target / 'version.json.tmp'
    temporary.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    temporary.chmod(0o644)
    temporary.replace(target / 'version.json')
except Exception:
    shutil.copy2(backup / 'index.html', page)
    for filename in ('salom-latest.apk', 'version.json'):
        if (backup / filename).exists(): shutil.copy2(backup / filename, target / filename)
    if windows_file and (backup / windows_file).exists(): shutil.copy2(backup / windows_file, target / windows_file)
    raise
print(json.dumps({'backup': str(backup), 'sha256': metadata['sha256'], 'size': metadata['size']}))
