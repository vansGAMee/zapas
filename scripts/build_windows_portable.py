"""Build a self-contained Windows x64 folder from official binary distributions.

No user library/profile is copied. May be run on Linux; Windows smoke test is
separate (Wine is useful but does not replace a real Windows machine).
"""
import json
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.private/windows-build'
OUT = ROOT / 'dist/Запас-Windows'
CACHE.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)

def fetch(url, dest):
    if not dest.exists():
        partial = dest.with_suffix(dest.suffix + '.part')
        with urllib.request.urlopen(url, timeout=60) as source, partial.open('wb') as target:
            shutil.copyfileobj(source, target, 1024*1024)
        partial.replace(dest)
    return dest

page = urllib.request.urlopen('https://www.python.org/downloads/windows/', timeout=30).read().decode()
versions = re.findall(r'https://www.python.org/ftp/python/(3\.13\.\d+)/python-\1-embed-amd64.zip', page)
if not versions:
    raise RuntimeError('Official Python 3.13 embedded distribution not found')
version = max(set(versions), key=lambda v: tuple(map(int, v.split('.'))))
print('Preparing Windows CPython', version, flush=True)
python_zip = fetch(f'https://www.python.org/ftp/python/{version}/python-{version}-embed-amd64.zip', CACHE / f'python-{version}.zip')
runtime = OUT / 'runtime'
runtime.mkdir(exist_ok=True)
with zipfile.ZipFile(python_zip) as archive:
    archive.extractall(runtime)
(runtime / 'python313._pth').write_text('python313.zip\n.\nLib/site-packages\n../desktop\nimport site\n')
wheels = CACHE / 'wheels'
wheels.mkdir(exist_ok=True)
subprocess.run([sys.executable, '-m', 'pip', 'download', '--disable-pip-version-check', '--only-binary=:all:', '--platform','win_amd64','--python-version','3.13','--implementation','cp','--abi','cp313','--dest',str(wheels),'-r',str(ROOT/'desktop/requirements.txt')], check=True)
packages = runtime / 'Lib/site-packages'
packages.mkdir(parents=True, exist_ok=True)
for wheel in wheels.glob('*.whl'):
    with zipfile.ZipFile(wheel) as archive:
        for member in archive.infolist():
            if member.is_dir():
                continue
            parts = Path(member.filename).parts
            if '..' in parts:
                raise RuntimeError('Unsafe wheel path')
            if parts[0].endswith('.data'):
                if len(parts) < 3 or parts[1] not in ('purelib','platlib'):
                    continue
                parts = parts[2:]
            dest = packages.joinpath(*parts)
            dest.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member) as source, dest.open('wb') as target:
                shutil.copyfileobj(source,target)
tools = OUT / 'tools'
tools.mkdir(exist_ok=True)
ffmpeg = fetch('https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip', CACHE/'ffmpeg.zip')
with zipfile.ZipFile(ffmpeg) as archive:
    for name in archive.namelist():
        if Path(name).name in ('ffmpeg.exe','ffprobe.exe','LICENSE'):
            (tools / Path(name).name).write_bytes(archive.read(name))
releases = json.load(urllib.request.urlopen('https://nodejs.org/dist/index.json',timeout=30))
node_version = next(v['version'] for v in releases if v['version'].startswith('v22.') and 'win-x64-exe' in v['files'])
fetch(f'https://nodejs.org/dist/{node_version}/win-x64/node.exe', tools/'node.exe')
shutil.copytree(ROOT/'desktop',OUT/'desktop',dirs_exist_ok=True,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
apk=ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'
if apk.exists():shutil.copy2(apk,OUT/'desktop/Zapas.apk')
for name in ('README.md','LICENSE','THIRD_PARTY_NOTICES.md'):
    shutil.copy2(ROOT/name,OUT/name)
(OUT/'Запустить.cmd').write_text('@echo off\r\ncd /d "%~dp0"\r\nstart "" "%~dp0runtime\\pythonw.exe" -m zapas %*\r\n',encoding='utf-8')
(OUT/'Проверить.cmd').write_text('@echo off\r\nchcp 65001 >nul\r\ncd /d "%~dp0"\r\n"%~dp0runtime\\python.exe" -m zapas --headless\r\npause\r\n',encoding='ascii')
shutil.copy2(ROOT/'scripts/windows-network.ps1', OUT/'setup-network.ps1')
(OUT/'Настроить-сеть.cmd').write_text('@echo off\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-network.ps1"\r\npause\r\n', encoding='ascii')
(OUT/'build-info.json').write_text(json.dumps({'python':version,'node':node_version,'platform':'windows-x64','app':'0.2.0'},indent=2))
print('Windows folder ready:', OUT, flush=True)
