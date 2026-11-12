import sys
import tempfile
import threading
from pathlib import Path

import uvicorn

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "desktop"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from zapas.server import create_app
from zapas.store import Store

root = tempfile.TemporaryDirectory(prefix="zapas-browser-")
store = Store(root.name)
if "--seed" in sys.argv:
    import subprocess
    from fixture import seed
    sample = Path(root.name) / "sample.mkv"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-filter_threads", "1", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-f", "lavfi", "-i", "sine=frequency=440", "-t", "2", "-c:v", "ffv1", "-threads", "1", "-c:a", "pcm_s16le", str(sample)], check=True)
    seed(root.name, sample)
app = create_app(store, start_worker=False, port=9474)
if "--fake-account" in sys.argv:
    class TestAccount:
        connected = "--seed" in sys.argv
        def state(self): return {"connected":self.connected,"login_open":False,"browser_available":True,"message":"Тестовое подключение YouTube"}
        def open_login(self):
            self.connected=True
            app.state.pipeline.enqueue_many([("https://www.youtube.com/watch?v=00000000001","Рекомендация 1"),("https://www.youtube.com/watch?v=00000000002","Рекомендация 2")])
        def disconnect(self): self.connected=False
        def stop(self): pass
    app.state.pipeline.account=TestAccount()
print("READY http://127.0.0.1:9474/#" + app.state.admin_token, flush=True)
uvicorn.run(app, host="127.0.0.1", port=9474, log_level="error")
