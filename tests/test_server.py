import hashlib
import time

import pytest
from fastapi.testclient import TestClient

from zapas.server import create_app
from zapas.store import Store


@pytest.fixture
def setup(tmp_path):
    store = Store(tmp_path)
    app = create_app(store, start_worker=False)
    with TestClient(app) as client:
        yield store, app, client, {"Authorization": "Bearer " + app.state.admin_token}


def ready(store, key="a" * 24, data=b"a video file used for range testing"):
    (store.root / "media" / f"{key}.mp4").write_bytes(data)
    (store.root / "thumbs" / f"{key}.jpg").write_bytes(b"jpeg")
    store.execute("INSERT INTO videos (id,title,state,created,media,thumb,bytes,sha256,duration) VALUES (?,?,'ready',?,?,?,?,?,120)", (key, "Тестовое видео", time.time(), f"{key}.mp4", f"{key}.jpg", len(data), hashlib.sha256(data).hexdigest()))
    return key, data


def pair(client, admin):
    code = client.post("/api/pairing", headers=admin).json()["code"]
    r = client.post("/api/pair", json={"code": code, "name": "Test Android"})
    assert r.status_code == 200
    return r.json(), {"Authorization": "Bearer " + r.json()["token"]}


def test_auth_pair_replay_and_revoke(setup):
    store, app, c, admin = setup
    assert c.get("/api/state").status_code == 401
    device, phone = pair(c, admin)
    assert c.get("/api/manifest", headers=phone).status_code == 200
    assert c.patch("/api/settings", headers=phone, json={"max_videos": 400}).status_code == 403
    assert c.post("/api/pair", json={"code": "000000"}).status_code == 403
    assert c.delete("/api/devices/" + device["device_id"], headers=admin).status_code == 200
    assert c.get("/api/manifest", headers=phone).status_code == 401
    assert store.one("SELECT token_hash FROM devices") is None


def test_pairing_throttle(setup):
    _, _, c, admin = setup
    code = c.post("/api/pairing", headers=admin).json()["code"]
    wrong = "111111" if code != "111111" else "222222"
    for _ in range(10):
        assert c.post("/api/pair", json={"code": wrong}).status_code == 403
    assert c.post("/api/pair", json={"code": code}).status_code == 403
    pair(c, admin)


def test_phone_transfer_pause_preserves_local_playback(setup):
    store, _, c, admin = setup
    key, data = ready(store)
    _, phone = pair(c, admin)
    assert c.patch('/api/settings',headers=admin,json={'transfers_paused':True,'paused':True}).status_code == 200
    assert c.get('/api/manifest',headers=phone).json()['transfers_paused'] is True
    assert c.get('/api/media/'+key,headers=phone).status_code == 423
    assert c.get('/api/media/'+key,headers=admin).content == data
    assert store.one('SELECT state FROM videos WHERE id=?',(key,))['state']=='ready'


def test_resumable_transfer_hash_and_ranges(setup):
    store, app, c, admin = setup
    key, data = ready(store)
    assert c.get(f"/api/media/{key}").status_code == 401
    _, phone = pair(c, admin)
    a = c.get(f"/api/media/{key}", headers=phone | {"Range": "bytes=0-9"})
    b = c.get(f"/api/media/{key}", headers=phone | {"Range": "bytes=10-"})
    assert a.status_code == b.status_code == 206
    assert a.content + b.content == data
    manifest = c.get("/api/manifest", headers=phone).json()
    assert hashlib.sha256(a.content + b.content).hexdigest() == manifest["videos"][0]["sha256"]
    assert a.headers["content-range"] == f"bytes 0-9/{len(data)}"
    assert c.get(f"/api/media/{key}", headers=phone | {"Range": "bytes=-5"}).content == data[-5:]
    for value in ("bytes=100000-", "bytes=-0", "bytes=4-2", "bytes=x-", "bytes=0-1,4-6"):
        assert c.get(f"/api/media/{key}", headers=phone | {"Range": value}).status_code == 416
    head = c.head(f"/api/media/{key}", headers=phone)
    assert not head.content and head.headers["content-length"] == str(len(data))


def test_media_cookie_never_grants_write_access(setup):
    store, _, c, admin = setup
    key, _ = ready(store)
    assert c.post("/api/session", headers=admin).status_code == 204
    assert c.get(f"/api/thumb/{key}").status_code == 200
    assert c.get(f"/api/media/{key}").status_code == 200
    assert c.get("/api/state").status_code == 401
    assert c.delete(f"/api/videos/{key}").status_code == 401


def test_progress_out_of_order_isolation_and_restart(setup):
    store, _, c, admin = setup
    key, _ = ready(store)
    d1, p1 = pair(c, admin)
    _, p2 = pair(c, admin)
    now = time.time()
    for position, update in [(60, now), (20, now - 10)]:
        assert c.post("/api/progress", headers=p1, json={"items": [{"video_id": key, "position": position, "updated": update}]}).status_code == 200
    assert c.get("/api/manifest", headers=p1).json()["progress"][0]["position"] == 60
    assert c.get("/api/manifest", headers=p2).json()["progress"] == []
    reopened = Store(store.root)
    assert reopened.one("SELECT position FROM progress WHERE device_id=?", (d1["device_id"],))["position"] == 60


def test_import_validation_limits_and_tombstone(setup):
    store, app, c, admin = setup
    for url in ("http://youtube.com/watch?v=x", "https://youtube.com.evil.test/x", "https://127.0.0.1/x", "file:///etc/passwd", "https://youtube.com@evil.test/"):
        assert c.post("/api/videos", headers=admin, json={"url": url}).status_code == 422
    assert c.patch("/api/settings", headers=admin, json={"max_videos": 1}).status_code == 200
    url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
    first = c.post("/api/videos", headers=admin, json={"url": url}).json()["id"]
    assert c.post("/api/videos", headers=admin, json={"url": "https://youtu.be/abcdefghijk"}).status_code == 409
    assert c.delete(f"/api/videos/{first}", headers=admin).status_code == 200
    app.state.pipeline.enqueue(url)
    assert store.one("SELECT state FROM videos WHERE id=?", (first,))["state"] == "archived"
    assert c.post("/api/videos", headers=admin, json={"url": url}).json()["id"] == first
    assert store.one("SELECT state FROM videos WHERE id=?", (first,))["state"] == "queued"
    for body in ({"max_gb": -1}, {"max_videos": True}, {"codec": "invalid"}, {"profile": []}):
        assert c.patch("/api/settings", headers=admin, json=body).status_code == 422


def test_phone_can_request_quality_and_recommendations(setup):
    _, _, c, admin = setup
    _, phone = pair(c, admin)
    assert c.post("/api/preferences", headers=phone, json={"profile": "compact", "codec": "h264"}).status_code == 200
    assert c.get("/api/state", headers=admin).json()["settings"]["profile"] == "compact"
    assert c.post("/api/preferences", headers=phone, json={"profile": "compact", "codec": "h264", "max_gb": 900}).status_code == 422
    video = {"url": "https://www.youtube.com/watch?v=aqz-KE-bpKQ", "title": "Big Buck Bunny"}
    assert c.post("/api/recommendations", headers=phone, json=[video]).json()["added"] == 1
    assert c.post("/api/recommendations", headers=phone, json=[video]).json()["added"] == 0
