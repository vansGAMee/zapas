import hashlib
import shutil
import subprocess

import pytest

from zapas.media import Pipeline, probe
from zapas.store import Store


@pytest.mark.skipif(not shutil.which("ffmpeg"), reason="FFmpeg required")
@pytest.mark.parametrize("codec", ["h264", "hevc", "av1"])
def test_real_local_encode_preserves_original_and_publishes_atomically(tmp_path, codec):
    src = tmp_path / "original.mkv"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-filter_threads", "1", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-f", "lavfi", "-i", "sine=frequency=440", "-t", "2", "-c:v", "ffv1", "-threads", "1", "-c:a", "pcm_s16le", str(src)], check=True, timeout=60)
    original_hash = hashlib.sha256(src.read_bytes()).hexdigest()
    store = Store(tmp_path / "library")
    store.save_settings({"codec": codec})
    pipe = Pipeline(store)
    key = pipe.enqueue(local=str(src))
    pipe.process(store.one("SELECT * FROM videos WHERE id=?", (key,)), store.settings())
    row = store.one("SELECT * FROM videos WHERE id=?", (key,))
    assert row["state"] == "ready", row["error"]
    final = store.root / "media" / row["media"]
    length, video = probe(final)
    assert video["codec_name"] == codec and video["height"] <= 720
    assert 1.9 < length < 2.2
    assert row["bytes"] < row["original_bytes"]
    assert hashlib.sha256(final.read_bytes()).hexdigest() == row["sha256"]
    assert hashlib.sha256(src.read_bytes()).hexdigest() == original_hash
    assert (store.root / "thumbs" / row["thumb"]).is_file()
    assert not list((store.root / "work").iterdir())


def test_bad_file_leaves_recoverable_error(tmp_path):
    src = tmp_path / "bad.mp4"
    src.write_bytes(b"this is not a video")
    store = Store(tmp_path / "library")
    pipe = Pipeline(store)
    key = pipe.enqueue(local=str(src))
    pipe.process(store.one("SELECT * FROM videos WHERE id=?", (key,)), store.settings())
    row = store.one("SELECT * FROM videos WHERE id=?", (key,))
    assert row["state"] == "error" and row["error"]
    assert not list((store.root / "media").iterdir())
