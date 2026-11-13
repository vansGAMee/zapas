import json
import os
import sqlite3
import sys
from contextlib import contextmanager
from pathlib import Path

DEFAULTS = {
    "profile": "balanced", "codec": "h264", "max_videos": 100,
    "max_gb": 16, "auto_refresh": True, "refresh_hours": 6,
    "browser": "", "paused": False, "download_mibps": 4, "autostart": False,
    "daily_limit": 80, "next_job_at": 0, "cooldown_until": 0, "auto_clean": True,
    "encoding_mode": "smart",
    "transfers_paused": False,
}


def data_dir():
    if os.getenv("ZAPAS_DATA"):
        return Path(os.environ["ZAPAS_DATA"])
    base = Path(os.getenv("LOCALAPPDATA", Path.home() / "AppData/Local")) if sys.platform == "win32" else Path(os.getenv("XDG_DATA_HOME", Path.home() / ".local/share"))
    return base / "zapas"


class Store:
    def __init__(self, root=None):
        self.root = Path(root or data_dir())
        self.root.mkdir(parents=True, exist_ok=True)
        if os.name != "nt":
            self.root.chmod(0o700)
        for p in ("media", "thumbs", "work"):
            (self.root / p).mkdir(exist_ok=True)
        self.path = self.root / "library.sqlite3"
        with self.db() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS videos (
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, channel TEXT DEFAULT '',
                    duration REAL DEFAULT 0, source_url TEXT DEFAULT '', local_path TEXT DEFAULT '',
                    state TEXT DEFAULT 'queued', progress REAL DEFAULT 0, error TEXT DEFAULT '',
                    bytes INTEGER DEFAULT 0, original_bytes INTEGER DEFAULT 0, sha256 TEXT DEFAULT '',
                    thumb TEXT DEFAULT '', media TEXT DEFAULT '', height INTEGER DEFAULT 0,
                    codec TEXT DEFAULT '', pinned INTEGER DEFAULT 0, created REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS sources (
                    id TEXT PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
                    enabled INTEGER DEFAULT 1, last_refresh REAL DEFAULT 0, error TEXT DEFAULT ''
                );
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
                    last_seen REAL NOT NULL, free_bytes INTEGER DEFAULT 0, count INTEGER DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS progress (
                    device_id TEXT NOT NULL, video_id TEXT NOT NULL, position REAL DEFAULT 0,
                    watched INTEGER DEFAULT 0, updated REAL NOT NULL,
                    PRIMARY KEY(device_id,video_id)
                );
                CREATE TABLE IF NOT EXISTS attempts (video_id TEXT NOT NULL, started REAL NOT NULL);
            """)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def all(self, sql, args=()):
        with self.db() as db:
            return [dict(x) for x in db.execute(sql, args)]

    def one(self, sql, args=()):
        rows = self.all(sql, args)
        return rows[0] if rows else None

    def execute(self, sql, args=()):
        with self.db() as db:
            db.execute(sql, args)

    def settings(self):
        return DEFAULTS | {r["key"]: json.loads(r["value"]) for r in self.all("SELECT * FROM settings")}

    def save_settings(self, settings):
        with self.db() as db:
            for k, v in settings.items():
                db.execute("INSERT OR REPLACE INTO settings VALUES (?,?)", (k, json.dumps(v)))

    def update_video(self, video_id, **fields):
        allowed = {"title", "channel", "duration", "state", "progress", "error", "bytes", "original_bytes", "sha256", "thumb", "media", "height", "codec", "pinned"}
        if not fields or not fields.keys() <= allowed:
            raise ValueError("Invalid video update")
        self.execute("UPDATE videos SET " + ",".join(f"{k}=?" for k in fields) + " WHERE id=?", (*fields.values(), video_id))
