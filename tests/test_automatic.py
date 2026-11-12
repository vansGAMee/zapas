import concurrent.futures
import time

from zapas.account import LoginRequired, PlatformLimited
from zapas.media import Pipeline, offline_reason
from zapas.store import Store


def test_personal_feed_requires_authenticated_session(tmp_path):
    s = Store(tmp_path)
    pipe = Pipeline(s)
    class Disconnected:
        def recommendations(self, *_):
            raise LoginRequired("Войди в YouTube")
    pipe.account = Disconnected()
    pipe.refresh()
    assert not s.all("SELECT id FROM videos")
    assert "Войди" in s.one("SELECT error FROM sources WHERE id='auto-recommendations'")["error"]


def test_feed_enqueues_automatically_deduplicates_and_caps(tmp_path):
    s = Store(tmp_path)
    s.save_settings({"max_videos": 50})
    pipe = Pipeline(s)
    class Connected:
        def recommendations(self, *_):
            return [{"url": f"https://www.youtube.com/watch?v={i:011d}", "title": f"Recommendation {i}"} for i in range(80)]
    pipe.account = Connected()
    pipe.refresh()
    assert len(s.all("SELECT id FROM videos")) == 50
    # A shortened URL for an already queued recommendation cannot duplicate it.
    assert pipe.enqueue_many([("https://youtu.be/00000000001", "Alias")])["existing"] == 1
    assert len(s.all("SELECT id FROM videos")) == 50
    assert s.one("SELECT last_refresh FROM sources")["last_refresh"] > 0


def test_import_quota_is_atomic_under_concurrent_feeds(tmp_path):
    s = Store(tmp_path)
    s.save_settings({"max_videos": 10})
    pipe = Pipeline(s)
    def batch(start):
        return pipe.enqueue_many([(f"https://www.youtube.com/watch?v={n:011d}", "Automatic") for n in range(start, start + 20)])
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(batch, [0, 100]))
    assert len(s.all("SELECT id FROM videos")) == 10


def test_rate_limit_causes_long_stop_without_retry_storm(tmp_path):
    s = Store(tmp_path)
    pipe = Pipeline(s)
    class Limited:
        def recommendations(self, *_):
            raise PlatformLimited("YouTube requested a pause")
    pipe.account = Limited()
    pipe.refresh()
    assert s.settings()["cooldown_until"] > time.time() + 11 * 3600
    assert not s.all("SELECT id FROM videos")
    assert "pause" in s.one("SELECT error FROM sources")["error"]
    # The downloader never receives the Google session.
    assert not {"cookiefile", "cookiesfrombrowser"} & pipe.options().keys()


def test_live_and_upcoming_videos_cannot_start_unbounded_downloads():
    assert offline_reason({"is_live":True}) == "deferred:live"
    assert offline_reason({"live_status":"is_upcoming"}) == "deferred:upcoming"
    assert offline_reason({"duration":8*3600}) == "skip:too-long"
    assert offline_reason({"live_status":"was_live","duration":3600}) == ""


def test_watched_rotation_preserves_pins_and_other_phones(tmp_path):
    s = Store(tmp_path)
    pipe = Pipeline(s)
    for n in range(2):
        key = f"{n:024d}"
        (s.root / "media" / f"{key}.mp4").write_bytes(b"video")
        (s.root / "thumbs" / f"{key}.jpg").write_bytes(b"image")
        s.execute("INSERT INTO videos (id,title,state,source_url,pinned,created,media,thumb) VALUES (?,?,'ready','https://www.youtube.com/watch?v=00000000001',?,?,?,?)", (key,"Automatic",n,time.time(),f"{key}.mp4",f"{key}.jpg"))
    for n in range(2):
        s.execute("INSERT INTO devices (id,name,token_hash,last_seen) VALUES (?,?,?,?)", (str(n),f"Phone {n}",str(n),time.time()))
    s.execute("INSERT INTO progress VALUES ('0',?,0,1,?)", ("0"*24,time.time()))
    pipe.clean_watched()
    assert s.one("SELECT state FROM videos WHERE id=?", ("0"*24,))["state"] == "ready"
    for phone, video in [("1","0"*24),("0",f"{1:024d}"),("1",f"{1:024d}")]:
        s.execute("INSERT INTO progress VALUES (?,?,0,1,?)", (phone,video,time.time()))
    pipe.clean_watched()
    assert s.one("SELECT state FROM videos WHERE id=?", ("0"*24,))["state"] == "archived"
    assert not (s.root / "media" / ("0"*24+".mp4")).exists()
    assert s.one("SELECT state FROM videos WHERE id=?", (f"{1:024d}",))["state"] == "ready"
