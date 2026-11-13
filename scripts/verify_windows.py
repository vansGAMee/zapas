"""Exercise the portable runtime without a Google account or network downloads.

Usage: <bundle>/runtime/python.exe scripts/verify_windows.py <bundle>
Runs on Windows and under Wine. Every write goes to a temporary fixture library.
"""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

bundle = Path(sys.argv[1]).resolve()
os.environ['PATH'] = str(bundle / 'tools') + os.pathsep + os.environ.get('PATH', '')
os.environ['QT_QPA_PLATFORM'] = 'offscreen'
from PySide6.QtWidgets import QApplication
from PySide6.QtWebEngineWidgets import QWebEngineView
from zapas.media import Pipeline, probe
from zapas.server import create_app
from zapas.store import Store
import uvicorn
import yt_dlp

qt = QApplication([])
assert QWebEngineView is not None
assert subprocess.check_output(['node', '-e', 'process.stdout.write("ok")'], timeout=20) == b'ok'

with tempfile.TemporaryDirectory(prefix='zapas-check-') as tmp:
    root = Path(tmp)
    src = root / 'source.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                    'color=c=blue:size=320x240:rate=25', '-t', '2', '-c:v',
                    'libx264', '-threads', '1', '-pix_fmt', 'yuv420p', str(src)], check=True, timeout=60)
    store = Store(root / 'library')
    store.save_settings({'codec': 'h264', 'profile': 'compact', 'encoding_mode': 'smart'})
    pipeline = Pipeline(store)
    key = pipeline.enqueue(local=str(src))
    pipeline.process(store.one('SELECT * FROM videos WHERE id=?', (key,)), store.settings())
    row = store.one('SELECT * FROM videos WHERE id=?', (key,))
    assert row['state'] == 'ready', row['error']
    video = store.root / 'media' / row['media']
    assert probe(video)[1]['codec_name'] == 'h264'
    expected = video.read_bytes()
    store.save_settings({'paused': True})
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    app = create_app(store, start_worker=False, port=port)
    server = uvicorn.Server(uvicorn.Config(app, host='127.0.0.1', port=port, log_level='error', access_log=False))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    try:
        for _ in range(100):
            if server.started:
                break
            time.sleep(.1)
        assert server.started, 'Server failed to start'
        # Disable environment proxies: these requests must remain on localhost.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        def request(path, token='', body=None, headers=None):
            values = {'Authorization': 'Bearer ' + token, **(headers or {})}
            data = None if body is None else json.dumps(body).encode()
            if data is not None:
                values['Content-Type'] = 'application/json'
            req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=data, headers=values)
            with opener.open(req, timeout=20) as response:
                return response.status, response.read()
        def document(path, token='', body=None):
            return json.loads(request(path, token, body)[1])
        assert document('/api/health')['app_version'] == '0.2.0'
        assert request('/')[0] == request('/phone')[0] == 200
        assert request('/api/install/android')[0] == 200
        try:
            request('/api/state')
            raise AssertionError('Unauthenticated admin request accepted')
        except urllib.error.HTTPError as error:
            assert error.code == 401
        code = document('/api/pairing', app.state.admin_token, {})['code']
        device = document('/api/pair', body={'code': code, 'name': 'Windows fixture'})
        token = device['token']
        manifest = document('/api/manifest', token)
        assert len(manifest['videos']) == 1
        status, part = request('/api/media/' + key, token, headers={'Range': 'bytes=10-'})
        assert status == 206 and part == expected[10:]
        assert hashlib.sha256(expected).hexdigest() == manifest['videos'][0]['sha256']
        store.save_settings({'transfers_paused': True})
        try:
            request('/api/media/' + key, token)
            raise AssertionError('Paused transfer accepted')
        except urllib.error.HTTPError as error:
            assert error.code == 423
    finally:
        server.should_exit = True
        thread.join(15)
        assert not thread.is_alive(), 'Server did not shut down'
    entry = Store(root / 'entry')
    entry.save_settings({'paused': True, 'transfers_paused': True, 'setup_complete': True})
    command = [sys.executable, '-m', 'zapas', '--headless', '--data', str(entry.root), '--port', str(port)]
    process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        for _ in range(100):
            assert process.poll() is None, 'Entry point exited before startup'
            try:
                if document('/api/health')['app_version'] == '0.2.0':
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise AssertionError('Entry point startup timed out')
        other = subprocess.run(command, capture_output=True, timeout=20)
        assert other.returncode == 2, 'Single instance lock failed'
    finally:
        process.terminate()
        process.wait(timeout=20)
        process.stderr.close()
print('WINDOWS_PORTABLE_OK: Qt, Node, FFmpeg, SQLite, HTTP, APK, pairing, Range, SHA256, transfer pause, entry point, process lock')
