import hashlib
import io
import os
import secrets
import shutil
import socket
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .media import PROFILES, Pipeline, youtube_url, canonical_video, is_source
from .store import Store


def lan_addresses(port):
    result = set()
    physical = set()
    try:
        from PySide6.QtNetwork import QNetworkInterface, QAbstractSocket
        for interface in QNetworkInterface.allInterfaces():
            flags = interface.flags()
            if not flags & QNetworkInterface.InterfaceFlag.IsRunning or flags & QNetworkInterface.InterfaceFlag.IsLoopBack:
                continue
            is_physical = interface.type() in (QNetworkInterface.InterfaceType.Ethernet, QNetworkInterface.InterfaceType.Wifi)
            for entry in interface.addressEntries():
                if entry.ip().protocol() == QAbstractSocket.NetworkLayerProtocol.IPv4Protocol:
                    ip = entry.ip().toString()
                    if ip.startswith(("127.", "169.254.")):
                        continue
                    result.add(ip)
                    if is_physical:
                        physical.add(ip)
    except (ImportError, RuntimeError):
        pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 80))  # no packets are sent
            result.add(s.getsockname()[0])
    except OSError:
        pass
    try:
        result.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError:
        pass
    return [f"http://{ip}:{port}" for ip in sorted(result, key=lambda ip: (ip not in physical, not ip.startswith("192.168."), ip)) if not ip.startswith("127.")]


class ImportBody(BaseModel):
    url: str = Field(max_length=2048)
    title: str = Field(default="", max_length=300)


class PairBody(BaseModel):
    code: str = Field(max_length=12)
    name: str = Field(default="Android", min_length=1, max_length=80)


class BatchBody(BaseModel):
    urls: list[str] = Field(min_length=1, max_length=500)


class ProgressBody(BaseModel):
    items: list[dict] = Field(default_factory=list, max_length=500)
    free_bytes: int = Field(default=0, ge=0)
    count: int = Field(default=0, ge=0, le=10000)


def create_app(store=None, start_worker=True, port=9473):
    s = store or Store()
    pipeline = Pipeline(s)
    admin_token = secrets.token_urlsafe(32)
    pairing = {"code": "", "expires": 0, "attempts": 0}

    @asynccontextmanager
    async def lifespan(app):
        if start_worker:
            pipeline.start()
        yield
        pipeline.stop.set()
        if pipeline.thread:
            pipeline.thread.join(timeout=20)
        pipeline.account.stop()

    app = FastAPI(title="Запас", version="0.1.0", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store, app.state.pipeline, app.state.admin_token = s, pipeline, admin_token

    @app.middleware("http")
    async def headers(request, call_next):
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["X-Frame-Options"] = "DENY"
        if request.url.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        else:
            response.headers["Content-Security-Policy"] = "default-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; style-src 'self'; script-src 'self' qrc:; connect-src 'self'; frame-ancestors 'none'"
        return response

    def auth(request: Request, authorization: str = Header(default="")):
        token = authorization.removeprefix("Bearer ")
        if not token and request.method in ("GET", "HEAD") and request.url.path.startswith(("/api/media/", "/api/thumb/")):
            token = request.cookies.get("zapas_media", "")
        if secrets.compare_digest(token, admin_token):
            return "admin"
        device = s.one("SELECT id FROM devices WHERE token_hash=?", (hashlib.sha256(token.encode()).hexdigest(),))
        if not device:
            raise HTTPException(401, "Подключи устройство заново")
        s.execute("UPDATE devices SET last_seen=? WHERE id=?", (time.time(), device["id"]))
        return device["id"]

    def admin(identity=Depends(auth)):
        if identity != "admin":
            raise HTTPException(403, "Это действие доступно на компьютере")
        return identity

    @app.post("/api/session", dependencies=[Depends(admin)])
    def session():
        response = Response(status_code=204)
        response.set_cookie("zapas_media", admin_token, httponly=True, samesite="strict", path="/api/", max_age=86400)
        return response

    @app.get("/api/health")
    def health():
        return {"name": "Запас", "version": 1}

    @app.get("/api/state", dependencies=[Depends(admin)])
    def state():
        return {"videos": s.all("SELECT * FROM videos WHERE state!='archived' ORDER BY created DESC"), "sources": s.all("SELECT * FROM sources ORDER BY title"), "devices": s.all("SELECT id,name,last_seen,free_bytes,count FROM devices ORDER BY last_seen DESC"), "settings": s.settings(), "account": pipeline.account.state(), "addresses": lan_addresses(port), "tools": {x: bool(shutil.which(x)) for x in ("ffmpeg", "ffprobe")}, "profiles": PROFILES}

    @app.post("/api/account/connect", dependencies=[Depends(admin)])
    def connect_account():
        if s.settings()["cooldown_until"] > time.time():
            raise HTTPException(429, "YouTube запросил паузу. Подключение будет доступно после её окончания.")
        try:
            pipeline.account.open_login()
        except Exception as e:
            raise HTTPException(409, str(e)) from None
        return pipeline.account.state()

    @app.delete("/api/account", dependencies=[Depends(admin)])
    def disconnect_account():
        pipeline.account.disconnect()
        s.execute("UPDATE sources SET error='YouTube отключён',last_refresh=? WHERE id='auto-recommendations'", (time.time(),))
        return {"ok": True}

    @app.get("/api/manifest")
    def manifest(identity=Depends(auth)):
        rows = s.all("SELECT id,title,channel,duration,bytes,sha256,height,codec,pinned,created FROM videos WHERE state='ready' ORDER BY pinned DESC,created DESC")
        progress = s.all("SELECT video_id,position,watched,updated FROM progress WHERE device_id=?", (identity,))
        return {"version": 1, "videos": rows, "progress": progress, "generated": time.time()}

    @app.post("/api/videos", dependencies=[Depends(admin)])
    def add_video(body: ImportBody):
        if not canonical_video(body.url):
            raise HTTPException(422, "Нужна HTTPS-ссылка на YouTube")
        result = pipeline.enqueue_many([(body.url, body.title)], restore=True)
        if result["limited"]:
            raise HTTPException(409, "Достигнут лимит видео. Увеличь его в настройках.")
        return {"id": result["ids"][0]}

    @app.post("/api/batch", dependencies=[Depends(admin)])
    def batch(body: BatchBody):
        videos, sources, rejected = [], [], []
        for url in dict.fromkeys(x.strip() for x in body.urls):
            if len(url) > 2048:
                rejected.append({"url": url[:80], "reason": "Слишком длинная ссылка"})
            elif canonical_video(url):
                videos.append((url, ""))
            elif is_source(url):
                sources.append(url)
            else:
                rejected.append({"url": url, "reason": "Нужна HTTPS-ссылка на ролик, плейлист или канал YouTube"})
        if not videos and not sources:
            raise HTTPException(422, "Нужна HTTPS-ссылка на YouTube: ролик, плейлист или канал")
        result = pipeline.enqueue_many(videos, restore=True)
        result.pop("ids")
        result["sources"] = 0
        with s.db() as db:
            for url in sources:
                if db.execute("SELECT id FROM sources WHERE url=?", (url,)).fetchone():
                    result["existing"] += 1
                elif db.execute("SELECT count(*) FROM sources").fetchone()[0] >= 50:
                    rejected.append({"url": url, "reason": "Достигнут лимит 50 источников"})
                else:
                    db.execute("INSERT INTO sources (id,url,title) VALUES (?,?,?)", (uuid.uuid4().hex, url, "Источник YouTube"))
                    result["sources"] += 1
        if result["sources"]:
            pipeline.refresh_requested.set()
        return result | {"rejected": rejected}

    @app.post("/api/videos/retry-failed", dependencies=[Depends(admin)])
    def retry_failed():
        count = s.one("SELECT count(*) AS n FROM videos WHERE state='error'")["n"]
        s.execute("UPDATE videos SET state='queued',error='',progress=0 WHERE state='error'")
        return {"count": count}

    @app.post("/api/recommendations", dependencies=[Depends(auth)])
    def recommendations(body: list[ImportBody]):
        if len(body) > 50 or any(not canonical_video(x.url) for x in body):
            raise HTTPException(422, "Допускается до 50 HTTPS-ссылок YouTube")
        result = pipeline.enqueue_many([(x.url, x.title) for x in body])
        return {"added": result["added"]}

    @app.get("/api/preferences", dependencies=[Depends(auth)])
    def device_preferences():
        cfg = s.settings()
        return {"profile": cfg["profile"], "codec": cfg["codec"]}

    @app.post("/api/preferences", dependencies=[Depends(auth)])
    def update_preferences(body: dict):
        if set(body) != {"profile", "codec"} or not isinstance(body.get("profile"), str) or body.get("profile") not in PROFILES or body.get("codec") not in ("h264", "hevc", "av1"):
            raise HTTPException(422, "Проверь профиль сжатия")
        s.save_settings(body)
        return body

    @app.post("/api/sources", dependencies=[Depends(admin)])
    def source(body: ImportBody):
        if not is_source(body.url):
            raise HTTPException(422, "Нужна HTTPS-ссылка на плейлист или канал YouTube")
        if s.one("SELECT count(*) AS n FROM sources")["n"] >= 50:
            raise HTTPException(409, "Можно добавить до 50 источников")
        key = uuid.uuid4().hex
        s.execute("INSERT OR IGNORE INTO sources (id,url,title) VALUES (?,?,?)", (key, body.url, body.title or "Плейлист YouTube"))
        pipeline.refresh_requested.set()
        return {"ok": True}

    @app.delete("/api/sources/{key}", dependencies=[Depends(admin)])
    def remove_source(key: str):
        s.execute("DELETE FROM sources WHERE id=?", (key,))
        return {"ok": True}

    @app.post("/api/refresh", dependencies=[Depends(admin)])
    def refresh():
        pipeline.refresh_requested.set()
        return {"ok": True}

    @app.patch("/api/settings", dependencies=[Depends(admin)])
    def settings(body: dict):
        validators = {
            "profile": lambda x: x in PROFILES, "codec": lambda x: x in ("h264", "hevc", "av1"),
            "max_videos": lambda x: type(x) is int and 1 <= x <= 500,
            "max_gb": lambda x: type(x) in (int, float) and 0.1 <= x <= 1000,
            "download_mibps": lambda x: type(x) in (int, float) and 0 <= x <= 100,
            "auto_refresh": lambda x: type(x) is bool, "paused": lambda x: type(x) is bool,
            "refresh_hours": lambda x: type(x) is int and 6 <= x <= 168,
            "daily_limit": lambda x: type(x) is int and 10 <= x <= 100,
            "auto_clean": lambda x: type(x) is bool,
            "browser": lambda x: x in ("", "firefox", "chrome", "chromium", "edge", "brave"),
        }
        try:
            valid = all(k in validators and validators[k](v) for k, v in body.items())
        except (TypeError, ValueError):
            valid = False
        if not valid:
            raise HTTPException(422, "Проверь значения настроек")
        s.save_settings(body)
        return s.settings()

    @app.post("/api/videos/{key}/retry", dependencies=[Depends(admin)])
    def retry(key: str):
        item = s.one("SELECT * FROM videos WHERE id=?", (key,))
        if not item or item["state"] != "error":
            raise HTTPException(409, "Видео уже готовится или готово")
        s.update_video(key, state="queued", error="", progress=0)
        return {"ok": True}

    @app.patch("/api/videos/{key}", dependencies=[Depends(admin)])
    def pin(key: str, body: dict):
        if type(body.get("pinned")) is not bool:
            raise HTTPException(422, "Нужно значение pinned")
        s.update_video(key, pinned=int(body["pinned"]))
        return {"ok": True}

    @app.delete("/api/videos/{key}", dependencies=[Depends(admin)])
    def delete(key: str):
        item = s.one("SELECT * FROM videos WHERE id=?", (key,))
        if item and item["state"] in ("downloading", "compressing"):
            raise HTTPException(409, "Дождись завершения подготовки")
        if item:
            # Tombstone keeps source refresh from downloading deleted items again.
            s.update_video(key, state="archived")
            for folder, field in (("media", "media"), ("thumbs", "thumb")):
                if item[field]:
                    (s.root / folder / Path(item[field]).name).unlink(missing_ok=True)
        return {"ok": True}

    @app.post("/api/pairing", dependencies=[Depends(admin)])
    def pairing_code():
        pairing.update(code=f"{secrets.randbelow(1000000):06d}", expires=time.time() + 300, attempts=0)
        return {"code": pairing["code"], "expires": pairing["expires"], "addresses": lan_addresses(port)}

    @app.get("/api/pairing/qr", dependencies=[Depends(admin)])
    def pairing_qr(address: str):
        import qrcode
        from urllib.parse import urlencode
        if address not in lan_addresses(port) or pairing["expires"] < time.time():
            raise HTTPException(400, "Создай новый код подключения")
        qr = qrcode.make("zapas://pair?" + urlencode({"address": address, "code": pairing["code"]}))
        buf = io.BytesIO()
        qr.save(buf, format="PNG")
        return Response(buf.getvalue(), media_type="image/png")

    @app.post("/api/pair")
    def pair(body: PairBody):
        pairing["attempts"] += 1
        if pairing["attempts"] > 10 or pairing["expires"] < time.time() or not secrets.compare_digest(body.code, pairing["code"]):
            raise HTTPException(403, "Код неверный или устарел. Создай новый код на ПК.")
        token, key = secrets.token_urlsafe(32), uuid.uuid4().hex
        s.execute("INSERT INTO devices (id,name,token_hash,last_seen) VALUES (?,?,?,?)", (key, body.name, hashlib.sha256(token.encode()).hexdigest(), time.time()))
        pairing["expires"] = 0
        return {"token": token, "device_id": key}

    @app.delete("/api/devices/{key}", dependencies=[Depends(admin)])
    def revoke(key: str):
        s.execute("DELETE FROM devices WHERE id=?", (key,))
        s.execute("DELETE FROM progress WHERE device_id=?", (key,))
        return {"ok": True}

    @app.post("/api/progress")
    def progress(body: ProgressBody, identity=Depends(auth)):
        with s.db() as db:
            for item in body.items:
                key = item.get("video_id")
                if not isinstance(key, str) or not db.execute("SELECT id FROM videos WHERE id=?", (key,)).fetchone():
                    continue
                try:
                    position, updated = float(item.get("position", 0)), min(time.time() + 60, float(item.get("updated", 0)))
                    if not (0 <= position <= 604800 and 0 <= updated <= time.time() + 60):
                        continue
                except (TypeError, ValueError):
                    continue
                db.execute("INSERT INTO progress VALUES (?,?,?,?,?) ON CONFLICT(device_id,video_id) DO UPDATE SET position=excluded.position,watched=excluded.watched,updated=excluded.updated WHERE excluded.updated>progress.updated", (identity, key, position, bool(item.get("watched")), updated))
            db.execute("UPDATE devices SET free_bytes=?,count=? WHERE id=?", (body.free_bytes, body.count, identity))
        return {"ok": True}

    @app.get("/api/thumb/{key}", dependencies=[Depends(auth)])
    def thumb(key: str):
        row = s.one("SELECT thumb FROM videos WHERE id=? AND state='ready'", (key,))
        path = s.root / "thumbs" / Path(row["thumb"]).name if row and row["thumb"] else None
        if not path or not path.is_file():
            raise HTTPException(404, "Обложка не найдена")
        return FileResponse(path, media_type="image/jpeg")

    @app.api_route("/api/media/{key}", methods=["GET", "HEAD"], dependencies=[Depends(auth)])
    def media(key: str, request: Request):
        row = s.one("SELECT media,sha256 FROM videos WHERE id=? AND state='ready'", (key,))
        path = s.root / "media" / Path(row["media"]).name if row and row["media"] else None
        if not path or not path.is_file():
            raise HTTPException(404, "Видео не найдено")
        size, start, end, status = path.stat().st_size, 0, path.stat().st_size - 1, 200
        headers = {"Accept-Ranges": "bytes", "ETag": '"' + row["sha256"] + '"', "Content-Type": "video/mp4"}
        value = request.headers.get("range")
        if value:
            try:
                if not value.startswith("bytes=") or "," in value:
                    raise ValueError()
                a, b = value[6:].split("-", 1)
                start, end = (int(a), min(int(b), size - 1) if b else size - 1) if a else (max(0, size - int(b)), size - 1)
                if start < 0 or start >= size or end < start:
                    raise ValueError()
            except ValueError:
                return Response(status_code=416, headers={"Content-Range": f"bytes */{size}"})
            status = 206
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
        headers["Content-Length"] = str(end - start + 1)
        if request.method == "HEAD":
            return Response(status_code=status, headers=headers)
        def chunks():
            with path.open("rb") as f:
                f.seek(start)
                left = end - start + 1
                while left:
                    block = f.read(min(left, 256 * 1024))
                    if not block:
                        break
                    left -= len(block)
                    yield block
        return StreamingResponse(chunks(), status_code=status, headers=headers)

    web = Path(__file__).resolve().parent.parent / "web"
    app.mount("/", StaticFiles(directory=web, html=True), name="web")
    return app
