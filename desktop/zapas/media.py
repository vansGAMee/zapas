import hashlib
import json
import logging
import math
import os
import re
import random
import shutil
import subprocess
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from .account import Account, LoginRequired, PlatformLimited

PROFILES = {
    "compact": {"height": 480, "crf": 29, "audio": "64k", "name": "Экономный"},
    "balanced": {"height": 720, "crf": 25, "audio": "96k", "name": "Баланс"},
    "quality": {"height": 1080, "crf": 21, "audio": "128k", "name": "Качество"},
}


def youtube_url(url):
    p = urlparse(url)
    return p.scheme == "https" and p.hostname in {"youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com"} and not p.username and not p.password


def canonical_video(url):
    if not youtube_url(url):
        return None
    p = urlparse(url)
    parts = p.path.strip("/").split("/")
    key = parts[0] if p.hostname == "youtu.be" else parse_qs(p.query).get("v", [""])[0] if p.path == "/watch" else parts[1] if len(parts) > 1 and parts[0] in ("shorts", "live") else ""
    return "https://www.youtube.com/watch?v=" + key if re.fullmatch(r"[\w-]{11}", key, flags=re.ASCII) else None


def is_source(url):
    if not youtube_url(url):
        return False
    p = urlparse(url)
    return (p.path == "/playlist" and bool(parse_qs(p.query).get("list"))) or p.path.startswith(("/@", "/channel/", "/c/", "/user/", "/feed/recommended"))


def offline_reason(info):
    if info.get("is_live") or info.get("live_status") in ("is_live", "post_live"):
        return "deferred:live"
    if info.get("live_status") == "is_upcoming":
        return "deferred:upcoming"
    if (info.get("duration") or 0) > 6 * 3600:
        return "skip:too-long"
    return ""


def probe(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(path)], capture_output=True, check=True, timeout=60)
    d = json.loads(r.stdout)
    v = next((s for s in d["streams"] if s["codec_type"] == "video"), None)
    if not v:
        raise ValueError("В файле нет видеодорожки")
    v['_audio'] = next((s for s in d['streams'] if s['codec_type']=='audio'), {})
    return float(d["format"].get("duration", 0)), v


def encode_args(src, dst, profile="balanced", codec="h264", mode="smart"):
    p = PROFILES[profile]
    height = p["height"]
    # Preserve aspect ratio, don't upscale, keep both dimensions even.
    scale = f"scale=w='trunc(iw*min(1,{height}/ih)/2)*2':h='trunc(ih*min(1,{height}/ih)/2)*2',fps=fps='min(source_fps,30)'"
    preset = "medium" if mode == "small" else "veryfast"
    enc = {"h264": ["-c:v", "libx264", "-preset", preset, "-crf", str(p["crf"])],
           "hevc": ["-c:v", "libx265", "-preset", preset, "-crf", str(p["crf"] + 2), "-tag:v", "hvc1", "-x265-params", "pools=2"],
           "av1": ["-c:v", "libsvtav1", "-preset", "8", "-crf", str(p["crf"] + 9), "-svtav1-params", "lp=2"]}[codec]
    return ["ffmpeg", "-hide_banner", "-y", "-filter_threads", "1", "-threads", "2", "-i", str(src), "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-vf", scale,
            *enc, "-pix_fmt", "yuv420p", "-threads", "2", "-c:a", "aac", "-b:a", p["audio"], "-ac", "2", "-movflags", "+faststart", "-progress", "pipe:1", "-nostats", str(dst)]


def can_copy_video(stream, profile, codec):
    try:
        rate = str(stream.get('avg_frame_rate', '0/1')).split('/')
        fps = float(rate[0]) / float(rate[1]) if len(rate) == 2 else float(rate[0])
        height = int(stream.get('height', 0))
    except (ValueError, TypeError, ZeroDivisionError):
        return False
    return stream.get('codec_name') == codec and 0 < height <= PROFILES[profile]['height'] and 0 < fps <= 30.1 and stream.get('pix_fmt') == 'yuv420p'


def can_copy_audio(stream, profile):
    try:
        bitrate, channels = int(stream.get('bit_rate', 0)), int(stream.get('channels', 0))
    except (ValueError, TypeError):
        return False
    return stream.get('codec_name') == 'aac' and 0 < bitrate <= int(PROFILES[profile]['audio'][:-1]) * 1000 and 0 < channels <= 2


def remux_args(src, dst, profile, copy_audio=False):
    audio = ['-c:a','copy'] if copy_audio else ['-c:a','aac','-b:a',PROFILES[profile]['audio'],'-ac','2']
    return ['ffmpeg','-hide_banner','-y','-threads','2','-i',str(src),'-map','0:v:0','-map','0:a:0?',
            '-c:v','copy',*audio,'-threads','2',
            '-movflags','+faststart','-progress','pipe:1','-nostats',str(dst)]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


class Pipeline:
    def __init__(self, store):
        self.s = store
        self.stop = threading.Event()
        self.refresh_requested = threading.Event()
        self.thread = None
        self.account = Account(store.root)
        self.last_cleanup = 0
        self.s.execute("INSERT OR IGNORE INTO sources (id,url,title) VALUES ('auto-recommendations','https://www.youtube.com/feed/recommended','Твои рекомендации YouTube')")

    def start(self):
        self.s.execute("UPDATE videos SET state='queued', progress=0 WHERE state IN ('downloading','compressing')")
        self.thread = threading.Thread(target=self.run, daemon=True, name="zapas-library")
        self.thread.start()

    def enqueue(self, url="", local="", title="", restore=False):
        if not local:
            url = canonical_video(url)
            if not url:
                raise ValueError("Добавь HTTPS-ссылку на ролик YouTube. Плейлист добавляется как источник.")
        key = hashlib.sha256((str(Path(local).resolve()) if local else url).encode()).hexdigest()[:24]
        self.s.execute("INSERT OR IGNORE INTO videos (id,title,source_url,local_path,created) VALUES (?,?,?,?,?)", (key, title or (Path(local).stem if local else "Получаем название…"), url, local, time.time()))
        if restore:
            self.s.execute("UPDATE videos SET state='queued',progress=0,error='',created=? WHERE id=? AND state='archived'", (time.time(), key))
        return key

    def enqueue_many(self, items, restore=False):
        result = {"added": 0, "existing": 0, "limited": 0, "ids": []}
        limit = self.s.settings()["max_videos"]
        with self.s.db() as db:
            # Take the write lock before counting: extension and manual imports share one quota.
            db.execute("BEGIN IMMEDIATE")
            count = db.execute("SELECT count(*) FROM videos WHERE state!='archived'").fetchone()[0]
            for raw, title in items:
                url = canonical_video(raw)
                if not url:
                    continue
                key = hashlib.sha256(url.encode()).hexdigest()[:24]
                old = db.execute("SELECT state,error,created FROM videos WHERE id=?", (key,)).fetchone()
                result["ids"].append(key)
                deferred_due = bool(old and old[0] == "archived" and old[1].startswith("deferred:") and old[2] < time.time() - 6 * 3600)
                if old and (old[0] != "archived" or not restore and not deferred_due):
                    result["existing"] += 1
                    continue
                if count >= limit:
                    result["limited"] += 1
                    continue
                if old:
                    db.execute("UPDATE videos SET state='queued',progress=0,error='',created=? WHERE id=?", (time.time(), key))
                else:
                    db.execute("INSERT INTO videos (id,title,source_url,created) VALUES (?,?,?,?)", (key, title or "Получаем название…", url, time.time()))
                count += 1
                result["added"] += 1
        return result

    def options(self):
        # Video downloading is deliberately anonymous. The Google session stays in its browser.
        opts = {"quiet": True, "no_warnings": True, "socket_timeout": 30, "retries": 1, "fragment_retries": 1, "extractor_retries": 0, "sleep_interval_requests": 1, "noprogress": True}
        rate = self.s.settings()["download_mibps"]
        if rate:
            opts["ratelimit"] = int(rate * 1024 * 1024)
        if shutil.which("node"):
            opts["js_runtimes"] = {"node": {}}
        elif shutil.which("deno"):
            opts["js_runtimes"] = {"deno": {}}
        return opts

    def protect(self, reason):
        self.s.save_settings({"cooldown_until": time.time() + 12 * 3600})
        self.s.execute("UPDATE sources SET error=?,last_refresh=? WHERE id='auto-recommendations'", (reason, time.time()))

    def clean_watched(self):
        phones = self.s.all("SELECT id FROM devices WHERE name NOT LIKE 'Расширение%' AND last_seen>?", (time.time() - 30 * 86400,))
        if not phones:
            return
        for video in self.s.all("SELECT * FROM videos WHERE state='ready' AND pinned=0 AND source_url!=''"):
            if all(self.s.one("SELECT watched FROM progress WHERE device_id=? AND video_id=? AND watched=1", (d["id"], video["id"])) for d in phones):
                self.s.update_video(video["id"], state="archived")
                for directory, field in (("media", "media"), ("thumbs", "thumb")):
                    if video[field]:
                        (self.s.root / directory / Path(video[field]).name).unlink(missing_ok=True)

    def refresh(self):
        import yt_dlp
        cfg = self.s.settings()
        for source in self.s.all("SELECT * FROM sources WHERE enabled=1"):
            if self.stop.is_set():
                return
            if self.s.one("SELECT count(*) AS n FROM videos WHERE state!='archived'")["n"] >= cfg["max_videos"]:
                return
            try:
                if source["id"] == "auto-recommendations":
                    # Even manual refresh cannot hammer the signed-in home page.
                    if time.time() - source["last_refresh"] < (5 * 60 if source["error"] else 20 * 60):
                        continue
                    recommendations = self.account.recommendations(cfg["max_videos"], self.stop)
                    self.enqueue_many([(v["url"], v["title"]) for v in recommendations])
                    self.s.execute("UPDATE sources SET last_refresh=?,error='' WHERE id=?", (time.time(), source["id"]))
                    continue
                with yt_dlp.YoutubeDL(self.options() | {"extract_flat": True, "playlistend": cfg["max_videos"]}) as y:
                    info = y.extract_info(source["url"], download=False)
                entries = info.get("entries", [info])
                candidates = []
                for e in entries:
                    if not e:
                        continue
                    url = e.get("webpage_url") or e.get("url", "")
                    if not url.startswith("https://") and e.get("id"):
                        url = "https://www.youtube.com/watch?v=" + e["id"]
                    if canonical_video(url):
                        candidates.append((url, e.get("title", "")))
                self.enqueue_many(candidates)
                self.s.execute("UPDATE sources SET title=?,last_refresh=?,error='' WHERE id=?", (info.get("title", source["title"]), time.time(), source["id"]))
            except PlatformLimited as e:
                self.protect(str(e))
                return
            except LoginRequired as e:
                self.s.execute("UPDATE sources SET last_refresh=?,error=? WHERE id=?", (time.time(), str(e), source["id"]))
            except Exception as e:
                self.s.execute("UPDATE sources SET last_refresh=?,error=? WHERE id=?", (time.time(), str(e)[-600:], source["id"]))

    def run(self):
        while not self.stop.is_set():
            try:
                cfg = self.s.settings()
                if self.account.poll_login():
                    self.s.execute("UPDATE sources SET last_refresh=0,error='' WHERE id='auto-recommendations'")
                    self.refresh_requested.set()
                if cfg["auto_clean"] and not cfg["paused"] and not cfg["transfers_paused"] and time.monotonic() - self.last_cleanup > 60:
                    self.last_cleanup = time.monotonic()
                    self.clean_watched()
                if cfg["paused"]:
                    self.stop.wait(2)
                    continue
                if cfg["cooldown_until"] > time.time():
                    self.stop.wait(5)
                    continue
                due = self.s.one("SELECT id FROM sources WHERE enabled=1 AND (last_refresh<? OR (error!='' AND last_refresh<?))", (time.time() - cfg["refresh_hours"] * 3600, time.time() - 300))
                if due and due['id'] == 'auto-recommendations' and not self.account.connected:
                    due = None
                if self.refresh_requested.is_set() or (cfg["auto_refresh"] and due):
                    self.refresh_requested.clear()
                    self.refresh()
                    cfg = self.s.settings()
                    if cfg["cooldown_until"] > time.time():
                        continue
                item = self.s.one("SELECT * FROM videos WHERE state='queued' ORDER BY created LIMIT 1")
                if not item:
                    item = self.s.one("SELECT v.* FROM videos v WHERE state='error' AND (SELECT count(*) FROM attempts a WHERE a.video_id=v.id AND a.started>?)<3 AND coalesce((SELECT max(started) FROM attempts a WHERE a.video_id=v.id),0)<? ORDER BY created LIMIT 1", (time.time()-86400,time.time()-1800))
                if item:
                    if cfg["next_job_at"] > time.time() or self.s.one("SELECT count(*) AS n FROM attempts WHERE started>?", (time.time() - 86400,))["n"] >= cfg["daily_limit"]:
                        self.stop.wait(5)
                        continue
                    used = self.s.one("SELECT coalesce(sum(bytes),0) AS n FROM videos WHERE state='ready'")["n"]
                    count = self.s.one("SELECT count(*) AS n FROM videos WHERE state='ready'")["n"]
                    if used >= cfg["max_gb"] * 1024**3 or count >= cfg['max_videos']:
                        self.stop.wait(5)
                        continue
                    self.s.execute("INSERT INTO attempts VALUES (?,?)", (item["id"], time.time()))
                    self.s.execute("DELETE FROM attempts WHERE started<?", (time.time() - 30 * 86400,))
                    self.process(item, cfg)
                    self.s.save_settings({"next_job_at": time.time() + random.uniform(45, 90)})
                else:
                    self.stop.wait(2)
            except Exception:
                logging.getLogger(__name__).exception("Library worker failed; retrying in three seconds")
                self.stop.wait(3)

    def process(self, item, cfg):
        key = item["id"]
        work = self.s.root / "work" / key
        work.mkdir(exist_ok=True)
        try:
            if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
                raise ValueError("Не найден FFmpeg. Установи его и перезапусти Запас.")
            self.s.update_video(key, state="downloading", error="", progress=0)
            if item["local_path"]:
                src = Path(item["local_path"])
            elif item['original_bytes'] and item['duration'] and (work / 'source.mp4').is_file():
                src = work / 'source.mp4'
            else:
                import yt_dlp
                def hook(d):
                    if self.stop.is_set() or self.s.settings()['paused']:
                        raise RuntimeError("Подготовка на паузе")
                    if shutil.disk_usage(self.s.root).free < 512 * 1024**2:
                        raise RuntimeError("На диске ПК осталось меньше 512 МБ. Освободи место и повтори загрузку.")
                    total = d.get("total_bytes") or d.get("total_bytes_estimate") or 1
                    self.s.update_video(key, progress=min(.99, d.get("downloaded_bytes", 0) / total))
                h = PROFILES[cfg["profile"]]["height"]
                skip_reason = ""
                def offline_filter(info, *, incomplete=False):
                    nonlocal skip_reason
                    skip_reason = offline_reason(info)
                    return "Этот ролик пока не подходит для офлайн-запаса" if skip_reason else None
                opts = self.options() | {"outtmpl": str(work / "source.%(ext)s"), "format": f"bv*[height<={h}]+ba/b[height<={h}]/b", "merge_output_format": "mp4", "noplaylist": True, "writethumbnail": True, "progress_hooks": [hook], "match_filter": offline_filter}
                with yt_dlp.YoutubeDL(opts) as y:
                    info = y.extract_info(item["source_url"], download=True)
                    if skip_reason:
                        self.s.update_video(key, state="archived", error=skip_reason, progress=0)
                        return
                    if not info:
                        raise RuntimeError("YouTube пока не отдал видео. Запас повторит попытку позже.")
                    src = Path(y.prepare_filename(info))
                if not src.exists():
                    src = next(p for p in work.glob("source.*") if p.suffix in {".mp4", ".webm", ".mkv"})
                self.s.update_video(key, title=info.get("title", item["title"]), channel=info.get("uploader", "YouTube"))
            duration, source_stream = probe(src)
            self.s.update_video(key, state="compressing", progress=0, duration=duration, original_bytes=src.stat().st_size)
            dst = work / "encoded.mp4"
            mode = cfg.get('encoding_mode', 'smart')
            compatible = can_copy_video(source_stream, cfg['profile'], cfg['codec'])
            audio = source_stream.get('_audio', {})
            copy_audio = can_copy_audio(audio, cfg['profile'])
            bitrate = src.stat().st_size * 8 / max(duration, 1)
            copy_video = compatible and (mode == 'fast' or mode == 'smart' and bitrate <= {'compact':800000,'balanced':1600000,'quality':3000000}[cfg['profile']])
            command = remux_args(src, dst, cfg['profile'], copy_audio) if copy_video else encode_args(src, dst, cfg['profile'], cfg['codec'], mode)
            # stderr goes to a file, avoiding a full-pipe deadlock on long encodes.
            with open(work / "ffmpeg.log", "w+") as log:
                proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=log, text=True,
                                        creationflags=subprocess.BELOW_NORMAL_PRIORITY_CLASS if os.name == "nt" else 0)
                if os.name == "posix":
                    try:
                        os.setpriority(os.PRIO_PROCESS, proc.pid, 15)
                    except (ProcessLookupError, PermissionError):
                        pass
                for line in proc.stdout:
                    if self.stop.is_set() or self.s.settings()['paused']:
                        proc.terminate()
                        proc.wait(timeout=15)
                        raise RuntimeError("Подготовка на паузе")
                    if shutil.disk_usage(self.s.root).free < 256 * 1024**2:
                        proc.terminate()
                        proc.wait(timeout=15)
                        raise RuntimeError("На диске ПК мало места. Освободи место и повтори подготовку.")
                    if line.startswith("out_time_us="):
                        try:
                            self.s.update_video(key, progress=min(.99, int(line.split("=")[1]) / 1_000_000 / max(1, duration)))
                        except ValueError:
                            pass
                if proc.wait() != 0:
                    log.seek(0)
                    raise RuntimeError("FFmpeg: " + log.read()[-600:])
            if compatible and not copy_video and dst.stat().st_size > src.stat().st_size:
                # Re-encoding an efficient source must not make the stored file larger.
                subprocess.run(remux_args(src, dst, cfg['profile'], copy_audio), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=max(120, duration))
            _, stream = probe(dst)
            total = self.s.one("SELECT coalesce(sum(bytes),0) AS n FROM videos WHERE state='ready'")["n"]
            if total + dst.stat().st_size > cfg["max_gb"] * 1024**3:
                raise ValueError("Недостаточно места в лимите медиатеки. Увеличь лимит или удали видео.")
            thumb = self.s.root / "thumbs" / f"{key}.jpg"
            artwork = next((p for p in work.glob("source.*") if p.suffix.lower() in {".jpg", ".jpeg", ".webp", ".png"}), None)
            if artwork:
                from PIL import Image
                with Image.open(artwork) as im:
                    im.thumbnail((640, 360))
                    im.convert("RGB").save(thumb, quality=82)
            else:
                subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(min(3, duration / 3)), "-i", str(dst), "-frames:v", "1", "-vf", "scale=640:-2", str(thumb)], capture_output=True, timeout=90, check=True)
            final = self.s.root / "media" / f"{key}.mp4"
            dst.replace(final)
            self.s.update_video(key, state="ready", progress=1, bytes=final.stat().st_size, sha256=sha256(final), media=final.name, thumb=thumb.name, height=stream["height"], codec=cfg["codec"], channel=item["channel"] or ("Личное видео" if item["local_path"] else self.s.one("SELECT channel FROM videos WHERE id=?", (key,))["channel"]))
        except Exception as e:
            message = str(e)[-800:]
            if re.search(r"HTTP Error (403|429)|too many requests|confirm you.re not a bot|unusual traffic|captcha", message, re.I):
                self.protect("YouTube ограничил запросы. Автоматика остановлена на 12 часов, без обхода проверок.")
            unavailable = bool(re.search(r"video unavailable|private video|video is not available|members.only|confirm your age|removed by", message, re.I))
            paused = self.stop.is_set() or self.s.settings()['paused']
            self.s.update_video(key, state="queued" if paused else "archived" if unavailable else "error", error='' if paused else message, progress=0)
        finally:
            final_state = self.s.one('SELECT state FROM videos WHERE id=?', (key,))
            if final_state and final_state['state'] in ('ready', 'archived'):
                shutil.rmtree(work, ignore_errors=True)
