"""Smoke-test the actual Qt shell without opening a user-facing window."""
import os
import sys
import tempfile
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
os.environ.setdefault("QTWEBENGINE_CHROMIUM_FLAGS", "--disable-gpu --disable-dev-shm-usage")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "desktop"))

import threading
import uvicorn
from PySide6.QtCore import QTimer, QUrl, QObject, Slot
from PySide6.QtWidgets import QApplication
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtWebChannel import QWebChannel
from zapas.server import create_app
from zapas.store import Store

root = tempfile.TemporaryDirectory(prefix="zapas-native-")
app = create_app(Store(root.name), start_worker=False, port=9475)
server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=9475, log_level="error"))
thread = threading.Thread(target=server.run, daemon=True)
thread.start()
qt = QApplication([])
view = QWebEngineView()
view.resize(1280, 900)
channel = QWebChannel()

class Bridge(QObject):
    @Slot()
    def importVideo(self):
        pass
    @Slot(bool, result=str)
    def setAutostart(self, enabled):
        return ""

bridge = Bridge()
channel.registerObject("desktop", bridge)
view.page().setWebChannel(channel)
result = {"passed": False}

def check():
    js = "JSON.stringify({title:document.querySelector('h1')?.textContent,bridge:!!window.qt,mp4:!!document.createElement('video').canPlayType('video/mp4; codecs=\\\"avc1.42E01E\\\"'),autostart:!document.querySelector('[data-action=autostart]')?.disabled})"
    def received(value):
        import json
        try:
            data = json.loads(value)
            print(data, flush=True)
            if data.get("title") == "Видео с собой" and data.get("bridge"):
                result["passed"] = True
                view.grab().save(str(Path(__file__).resolve().parents[1] / "artifacts/native-shell.png"))
                server.should_exit = True
                qt.quit()
                return
        except Exception:
            pass
        QTimer.singleShot(500, check)
    view.page().runJavaScript(js, received)

def load():
    if not server.started:
        QTimer.singleShot(100, load)
        return
    view.setUrl(QUrl("http://127.0.0.1:9475/#" + app.state.admin_token))
    QTimer.singleShot(1500, check)

QTimer.singleShot(25000, qt.quit)
load()
qt.exec()
server.should_exit = True
thread.join(5)
assert result["passed"], "Qt failed to load the real application"
print("NATIVE_SHELL_OK")
