"""Read the real YouTube home in a dedicated browser profile.

Google credentials stay in Chrome/Chromium/Edge. Only public video links and
titles cross the local DevTools connection. No likes, watches or account writes.
"""
import json
import logging
import os
import shutil
import socket
import subprocess
import threading
import time
import urllib.request
from pathlib import Path


CAPTURE_SCRIPT = """(()=>({loggedIn:(()=>{const v=window.ytcfg?.get?.('LOGGED_IN');return typeof v==='boolean'?v:document.querySelector('#avatar-btn img')?true:null})(), limited:!!document.querySelector('iframe[src*=recaptcha]')||/confirm you.re not a bot|unusual traffic|подтвердите.{0,30}не робот/i.test(document.body?.innerText||''), videos:[...document.querySelectorAll('ytd-rich-item-renderer a[href*="/watch?v="],yt-lockup-view-model a[href*="/watch?v="]')].map(a=>{let id=new URL(a.href).searchParams.get('v');let card=a.closest('ytd-rich-item-renderer,yt-lockup-view-model');return {url:'https://www.youtube.com/watch?v='+id,title:card?.querySelector('#video-title,h3')?.textContent?.trim()||a.getAttribute('title')||''}}).filter(v=>/^https:\\/\\/www\\.youtube\\.com\\/watch\\?v=[\\w-]{11}$/.test(v.url))}))()"""

class LoginRequired(Exception):
    pass


class PlatformLimited(Exception):
    pass


def find_browser():
    for name in ("chromium", "google-chrome", "google-chrome-stable", "chromium-browser", "chrome", "msedge"):
        path = shutil.which(name)
        if path:
            return path
    if os.name == "nt":
        for base in (os.getenv("PROGRAMFILES", ""), os.getenv("PROGRAMFILES(X86)", ""), os.getenv("LOCALAPPDATA", "")):
            for suffix in ("Google/Chrome/Application/chrome.exe", "Microsoft/Edge/Application/msedge.exe", "Chromium/Application/chrome.exe"):
                path = Path(base) / suffix
                if path.is_file():
                    return str(path)
    return None


class CDP:
    def __init__(self, url):
        from websockets.sync.client import connect
        self.context = connect(url, open_timeout=6, max_size=16 * 1024**2, proxy=None)
        self.ws = self.context.__enter__()
        self.number = 0
        self.limited = False

    def call(self, method, params=None):
        self.number += 1
        number = self.number
        self.ws.send(json.dumps({"id": number, "method": method, "params": params or {}}))
        while True:
            answer = json.loads(self.ws.recv(timeout=15))
            if answer.get("method") == "Network.responseReceived":
                response = answer.get("params", {}).get("response", {})
                if response.get("status") in (403, 429) and response.get("url", "").startswith("https://www.youtube.com/"):
                    self.limited = True
            if answer.get("id") != number:
                continue
            if "error" in answer:
                raise RuntimeError("Не удалось прочитать страницу YouTube")
            return answer.get("result", {})

    def evaluate(self, expression):
        result = self.call("Runtime.evaluate", {"expression": expression, "returnByValue": True})
        if self.limited:
            raise PlatformLimited("YouTube ограничил запросы. Автоматика остановлена на 12 часов.")
        if "exceptionDetails" in result:
            return None
        return result.get("result", {}).get("value")

    def close(self):
        self.context.__exit__(None, None, None)


class Account:
    def __init__(self, root):
        self.root = Path(root) / "youtube-profile"
        self.root.mkdir(exist_ok=True)
        if os.name != "nt":
            self.root.chmod(0o700)
        self.marker = self.root / "connected"
        self.process = None
        self.headed = False
        self.connected = self.marker.exists()
        self.message = "YouTube подключён" if self.connected else "Войди в YouTube один раз — дальше всё автоматически"
        self.lock = threading.RLock()
        self.last_poll = 0
        self.login_detected = False

    def state(self):
        return {"connected": self.connected, "message": self.message, "browser_available": bool(find_browser()), "login_open": bool(self.process and self.process.poll() is None and self.headed)}

    def stop(self):
        with self.lock:
            if self.process and self.process.poll() is None:
                # Browser.close flushes the profile. SIGTERM immediately after sign-in
                # could discard the new session before Chrome has written it to disk.
                page = None
                try:
                    page = self._page()
                    page.call("Browser.close")
                except Exception:
                    pass
                finally:
                    if page:
                        try:
                            page.close()
                        except Exception:
                            pass
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        self.process.kill()
                        self.process.wait(timeout=5)
            self.process = None

    def _start(self, headed=False):
        if self.process and self.process.poll() is None:
            if self.headed == headed:
                return
            self.stop()
        browser = find_browser()
        if not browser:
            raise LoginRequired("Установи Chrome, Chromium или Edge, чтобы подключить YouTube")
        port_file = self.root / "DevToolsActivePort"
        port_file.unlink(missing_ok=True)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        args = [browser, "--user-data-dir=" + str(self.root), f"--remote-debugging-port={port}", "--remote-debugging-address=127.0.0.1", "--no-first-run", "--no-default-browser-check", "--autoplay-policy=user-gesture-required"]
        if not headed:
            args += ["--headless=new", "--disable-gpu", "--disable-dev-shm-usage"]
        args += ["https://www.youtube.com/" if headed else "about:blank"]
        self.process = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                        creationflags=subprocess.BELOW_NORMAL_PRIORITY_CLASS if os.name == "nt" else 0)
        if os.name == "posix":
            try:
                os.setpriority(os.PRIO_PROCESS, self.process.pid, 15)
            except ProcessLookupError:
                pass
        self.headed = headed
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                with opener.open(f"http://127.0.0.1:{port}/json/version", timeout=.5) as response:
                    info = json.load(response)
                if info.get("webSocketDebuggerUrl"):
                    port_file.write_text(str(port) + "\n")
                    return
            except OSError:
                pass
            if self.process.poll() is not None:
                break
            time.sleep(.15)
        raise RuntimeError("Браузер YouTube не запустился. Попробуй подключение ещё раз.")

    def _page(self):
        port_file = self.root / "DevToolsActivePort"
        try:
            port = int(port_file.read_text().splitlines()[0])
            # No proxy for the loopback debugging connection.
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(f"http://127.0.0.1:{port}/json/list", timeout=5) as response:
                pages = json.load(response)
            tabs = [p for p in pages if p.get("type") == "page"]
            page = next((p for p in tabs if p.get("url", "").startswith("https://www.youtube.com/")), tabs[0] if tabs else None)
            if not page:
                raise StopIteration()
            return CDP(page["webSocketDebuggerUrl"])
        except (OSError, ValueError, StopIteration):
            raise LoginRequired("Заверши вход в открытом окне YouTube") from None

    def open_login(self):
        with self.lock:
            self.login_detected = False
            self.message = "Войди в свой аккаунт в открывшемся окне YouTube"
            self._start(headed=True)

    def poll_login(self):
        if self.login_detected or not self.headed or not self.process or self.process.poll() is not None or time.monotonic() - self.last_poll < 5:
            return False
        self.last_poll = time.monotonic()
        with self.lock:
            page = None
            try:
                page = self._page()
                logged_in = page.evaluate("(()=>{const v=window.ytcfg?.get?.('LOGGED_IN');return typeof v==='boolean'?v:document.querySelector('#avatar-btn img')?true:null})()")
                if not logged_in:
                    return False
                self.marker.write_text("1")
                self.connected = True
                self.login_detected = True
                self.message = "YouTube подключён. Готовим твои рекомендации."
                return True
            except Exception:
                return False
            finally:
                if page:
                    page.close()

    def recommendations(self, limit, stop=None):
        with self.lock:
            if not self.connected:
                raise LoginRequired(self.message)
            # Read the already authenticated tab first. Never replace a sign-in
            # window with another browser while Google is finishing sign-in.
            reuse_login = bool(self.headed and self.process and self.process.poll() is None)
            if not reuse_login:
                self._start(headed=False)
            page = self._page()
            try:
                page.call("Network.enable")
                page.call("Network.setBlockedURLs", {"urls": ["*://*.googlevideo.com/*"]})
                page.call("Page.addScriptToEvaluateOnNewDocument", {"source": "HTMLMediaElement.prototype.play=function(){return Promise.reject(new DOMException('User gesture required','NotAllowedError'))};"})
                if not reuse_login:
                    page.call("Page.navigate", {"url": "https://www.youtube.com/"})
                deadline = time.monotonic() + 45
                data = None
                while time.monotonic() < deadline:
                    if stop and stop.is_set():
                        return []
                    data = page.evaluate(CAPTURE_SCRIPT)
                    if data and data.get("limited"):
                        raise PlatformLimited("YouTube запросил подтверждение. Автоматика остановлена на 12 часов.")
                    if data and data.get("loggedIn") is True and data.get("videos"):
                        break
                    time.sleep(1)
                if data and data.get("loggedIn") is False:
                    self.connected = False
                    self.marker.unlink(missing_ok=True)
                    self.message = "YouTube вышел из аккаунта. Нажми «Подключить YouTube», чтобы войти снова."
                    raise LoginRequired(self.message)
                if not data or data.get("loggedIn") is not True or not data.get("videos"):
                    raise RuntimeError("YouTube пока не отдал рекомендации. Повторим позже, без частых запросов.")
                unique = {v["url"]: v for v in data["videos"]}
                # At most three continuation requests, separated by seven seconds.
                for _ in range(3):
                    if len(unique) >= min(limit, 100):
                        break
                    page.evaluate("window.scrollTo(0,document.documentElement.scrollHeight)")
                    if stop:
                        if stop.wait(7):
                            return []
                    else:
                        time.sleep(7)
                    more = page.evaluate(CAPTURE_SCRIPT) or {}
                    if more.get("limited"):
                        raise PlatformLimited("YouTube запросил подтверждение. Автоматика остановлена на 12 часов.")
                    if more.get("loggedIn") is not True:
                        # Keep the known-good initial feed on a failed continuation.
                        break
                    previous = len(unique)
                    unique.update({v["url"]: v for v in more.get("videos", [])})
                    if len(unique) == previous:
                        break
                result = list(unique.values())[:limit]
                self.connected = True
                self.marker.write_text("1")
                self.message = "YouTube подключён. Рекомендации обновляются автоматически."
                return result
            finally:
                page.close()
                # The user's sign-in window is never force-closed. Its profile is
                # reused on the next pass; background sessions close gracefully.
                if not reuse_login:
                    self.stop()

    def disconnect(self):
        self.stop()
        self.marker.unlink(missing_ok=True)
        self.connected = False
        self.message = "YouTube отключён"
        # Remove only the dedicated app profile, including its Google session.
        shutil.rmtree(self.root, ignore_errors=True)
        self.root.mkdir(exist_ok=True)
