import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request

from zapas.store import Store


def test_actual_desktop_entry_and_single_instance(tmp_path):
    store = Store(tmp_path / "data")
    store.save_settings({"setup_complete": True})
    env = os.environ | {"PYTHONPATH": str(Path(__file__).resolve().parents[1] / "desktop"), "QT_QPA_PLATFORM": "offscreen", "QTWEBENGINE_CHROMIUM_FLAGS": "--disable-gpu --disable-dev-shm-usage", "XDG_CONFIG_HOME": str(tmp_path / "config")}
    cmd = [sys.executable,"-m","zapas","--data",str(store.root),"--port","9476"]
    proc = subprocess.Popen(cmd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise AssertionError(proc.stderr.read().decode())
            try:
                with urllib.request.urlopen("http://127.0.0.1:9476/api/health",timeout=.5) as response:
                    assert response.status == 200
                break
            except OSError:
                time.sleep(.1)
        else:
            raise AssertionError("Desktop startup timed out")
        other = subprocess.run(cmd + ["--headless"],env=env,capture_output=True,timeout=10)
        assert other.returncode == 2
        assert "уже работает" in other.stderr.decode()
    finally:
        proc.terminate()
        proc.wait(timeout=10)
