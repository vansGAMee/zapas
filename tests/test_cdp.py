import shutil
import subprocess
import time

import pytest

from zapas.account import Account, CAPTURE_SCRIPT, LoginRequired


@pytest.mark.skipif(not shutil.which("chromium"), reason="Local Chromium required")
def test_real_browser_connection_reads_feed_without_touching_credentials(tmp_path):
    account = Account(tmp_path)
    proc = subprocess.Popen([shutil.which("chromium"), "--headless=new", "--disable-gpu", "--disable-dev-shm-usage", "--renderer-process-limit=1", "--no-first-run", "--no-default-browser-check", "--remote-debugging-port=0", "--user-data-dir=" + str(account.root), "about:blank"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    account.process = proc
    page = None
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                page = account._page()
                break
            except LoginRequired:
                time.sleep(.1)
        assert page is not None
        page.evaluate("""document.body.innerHTML='<yt-lockup-view-model><h3>Personal recommendation</h3><a href="https://www.youtube.com/watch?v=00000000001">video</a><a href="https://www.youtube.com/watch?v=bad">invalid</a></yt-lockup-view-model>';window.ytcfg={get:(key)=>key==='LOGGED_IN'};true""")
        result = page.evaluate(CAPTURE_SCRIPT)
        assert result["loggedIn"] is True and result["limited"] is False
        assert result["videos"] == [{"url":"https://www.youtube.com/watch?v=00000000001","title":"Personal recommendation"}]
        page.evaluate("document.body.appendChild(Object.assign(document.createElement('iframe'),{src:'about:blank#recaptcha'}));true")
        assert page.evaluate(CAPTURE_SCRIPT)["limited"] is True
    finally:
        if page:
            page.close()
        account.stop()
