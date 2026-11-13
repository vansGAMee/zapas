import hashlib
import shutil
import subprocess

import pytest

from zapas.media import Pipeline, probe, can_copy_video, can_copy_audio
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


def test_smart_copy_requires_matching_codec_resolution_and_framerate():
    v={'codec_name':'av1','height':480,'avg_frame_rate':'30/1','pix_fmt':'yuv420p'}
    assert can_copy_video(v,'compact','av1')
    assert not can_copy_video(v,'compact','h264')
    assert not can_copy_video(v|{'height':1080},'compact','av1')
    assert not can_copy_video(v|{'avg_frame_rate':'60/1'},'compact','av1')
    assert not can_copy_video(v|{'pix_fmt':'yuv420p10le'},'compact','av1')
    assert not can_copy_video(v|{'avg_frame_rate':'0/0'},'compact','av1')
    assert not can_copy_video(v|{'avg_frame_rate':'N/A'},'compact','av1')
    assert not can_copy_video(v|{'height':None},'compact','av1')


def test_unknown_audio_metadata_falls_back_to_encoding():
    audio = {'codec_name': 'aac', 'bit_rate': '48000', 'channels': 2}
    assert can_copy_audio(audio, 'compact')
    for bitrate in ('N/A', None, '', '0', '128000'):
        assert not can_copy_audio(audio | {'bit_rate': bitrate}, 'compact')
    assert not can_copy_audio(audio | {'channels': 6}, 'compact')


@pytest.mark.skipif(not shutil.which('ffmpeg'), reason='FFmpeg required')
def test_smart_mode_preserves_compact_video_bitstream(tmp_path):
    src = tmp_path / 'compact.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                    'color=c=blue:size=320x240:rate=25', '-t', '2', '-c:v', 'libx264',
                    '-threads', '1', '-pix_fmt', 'yuv420p', str(src)], check=True, timeout=60)
    store = Store(tmp_path / 'library')
    store.save_settings({'profile': 'compact', 'codec': 'h264', 'encoding_mode': 'smart'})
    pipe = Pipeline(store)
    key = pipe.enqueue(local=str(src))
    pipe.process(store.one('SELECT * FROM videos WHERE id=?', (key,)), store.settings())
    row = store.one('SELECT * FROM videos WHERE id=?', (key,))
    assert row['state'] == 'ready', row['error']
    def video_hash(path):
        return subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(path),
                                        '-map', '0:v:0', '-c', 'copy', '-f', 'hash', '-hash', 'sha256', '-'], timeout=30)
    assert video_hash(src) == video_hash(store.root / 'media' / row['media'])
