from pathlib import Path
import datetime
import shutil
import sqlite3
import subprocess

source = Path('/tmp/salom-api-deploy')
assert (source / 'app.py').is_file()
if subprocess.run(['id', '-u', 'salom-api'], capture_output=True).returncode:
    subprocess.run(['useradd', '--system', '--home-dir', '/var/lib/salom-api', '--shell', '/usr/sbin/nologin', 'salom-api'], check=True)
destination = Path('/opt/salom-api')
destination.mkdir(exist_ok=True)
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup = Path('/var/backups') / ('salom-api-' + stamp)
backup.mkdir(mode=0o700)
config = Path('/etc/nginx/sites-available/ryadom')
shutil.copy2(config, backup / 'nginx.conf')
if (destination / 'app.py').exists(): shutil.copy2(destination / 'app.py', backup / 'app.py')
database = Path('/var/lib/salom-api/directory.sqlite3')
if database.exists():
    with sqlite3.connect(database) as origin, sqlite3.connect(backup / 'directory.sqlite3') as saved:
        origin.backup(saved)
for filename in ('app.py', 'requirements.txt'):
    shutil.copy2(source / filename, destination / filename)
    (destination / filename).chmod(0o644)
shutil.copy2(source / 'salom-api.service', '/etc/systemd/system/salom-api.service')
subprocess.run(['systemctl', 'daemon-reload'], check=True)
subprocess.run(['systemctl', 'enable', '--now', 'salom-api'], check=True)
subprocess.run(['systemctl', 'restart', 'salom-api'], check=True)
text = config.read_text()
if 'location ^~ /salom/api/' not in text:
    anchor = '    # Salom downloads\n'
    assert anchor in text
    text = text.replace(anchor, '''    # Salom directory API (independent of APK routes)
    location ^~ /salom/api/ {
        proxy_pass http://127.0.0.1:18731/;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 8k;
        proxy_connect_timeout 3s;
        proxy_read_timeout 12s;
        add_header Cache-Control no-store;
    }
''' + anchor, 1)
try:
    config.write_text(text)
    subprocess.run(['nginx', '-t'], check=True)
    subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
except Exception:
    shutil.copy2(backup / 'nginx.conf', config)
    raise
print('DEPLOYED backup=' + str(backup))
