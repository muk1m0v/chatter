"""Run on the authorized VPS after uploading index.html and the APK to /tmp/salom-deploy.
Preserves the existing proxy routes and makes backups before nginx changes.
"""
from pathlib import Path
import datetime
import hashlib
import json
import shutil
import subprocess

incoming = Path('/tmp/salom-deploy')
apk = incoming / 'salom-0.9.0.apk'
assert apk.is_file() and apk.stat().st_size > 100_000
assert (incoming / 'index.html').is_file()
config = Path('/etc/nginx/sites-available/ryadom')
page = Path('/var/www/mukimov-test/index.html')
assert 'server_name mukimov.site;' in config.read_text()
backup = Path('/var/backups') / ('salom-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
backup.mkdir(parents=True, exist_ok=False)
shutil.copy2(config, backup / 'nginx.conf')
shutil.copy2(page, backup / 'index.html')
target = Path('/var/www/salom')
target.mkdir(parents=True, exist_ok=True)
for filename in ('salom-latest.apk', 'version.json'):
    if (target / filename).exists(): shutil.copy2(target / filename, backup / filename)
sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
if (target / apk.name).exists():
    assert hashlib.sha256((target / apk.name).read_bytes()).hexdigest() == sha256, 'A published version must never change bytes'
for filename in (apk.name, 'salom-latest.apk'):
    temporary = target / (filename + '.tmp')
    shutil.copy2(apk, temporary)
    temporary.chmod(0o644)
    temporary.replace(target / filename)
metadata = {'version': '0.9.0', 'versionCode': 11, 'minSdk': 24, 'downloadUrl': 'https://mukimov.site/salom/' + apk.name, 'size': apk.stat().st_size, 'sha256': sha256,
            'releaseNotes': ['Одна кнопка входа через Google', 'Выбор свободного username при первом входе', 'Подключение Google к существующему профилю с сохранением чатов']}
for file in target.iterdir():
    if file.is_file(): file.chmod(0o644)
new_config = config.read_text()
marker = '    # Salom downloads\n'
anchor = '    location /static/ {'
if marker in new_config:
    start = new_config.index(marker)
    end = new_config.index(anchor, start)
    new_config = new_config[:start] + new_config[end:]
if marker not in new_config:
    routes = marker
    for filename in sorted(file.name for file in target.glob('salom-*.apk')):
        download_name = 'salom-0.9.0.apk' if filename == 'salom-latest.apk' else filename
        routes += f'''    location = /salom/{filename} {{
        alias /var/www/salom/{filename};
        default_type application/vnd.android.package-archive;
        add_header Content-Disposition 'attachment; filename="{download_name}"';
        add_header Cache-Control 'no-store';
        add_header X-Content-Type-Options nosniff always;
    }}
'''
    routes += '''    location = /salom/version.json {
        alias /var/www/salom/version.json;
        default_type application/json;
        add_header Cache-Control 'no-store';
    }
'''
    anchor = '    location /static/ {'
    assert anchor in new_config
    new_config = new_config.replace(anchor, routes + anchor, 1)
try:
    config.write_text(new_config)
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
    shutil.copy2(backup / 'nginx.conf', config)
    shutil.copy2(backup / 'index.html', page)
    for filename in ('salom-latest.apk', 'version.json'):
        if (backup / filename).exists(): shutil.copy2(backup / filename, target / filename)
    subprocess.run(['nginx', '-t'], check=True)
    subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
    raise
print(json.dumps({'backup': str(backup), 'sha256': metadata['sha256'], 'size': metadata['size']}))
