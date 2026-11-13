"""Real media transfer fixture for the Android integration run. Local machine only."""
import json
import shutil
import sys
import time
from pathlib import Path

import uvicorn

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'desktop'))
from zapas.store import Store
from zapas.server import create_app
from zapas.media import sha256, probe

root = Path(__file__).resolve().parents[1]
data = root / 'artifacts/test-library/android-connection'
store = Store(data)
app = create_app(store, start_worker=False, port=19473)
sample = next((root / 'artifacts/test-library/live/media').glob('*.mp4'))
thumb = next((root / 'artifacts/test-library/live/thumbs').glob('*.jpg'))
duration, info = probe(sample)
for i in range(2):
    key = f'{i+1:024x}'
    shutil.copy2(sample, data / 'media' / f'{key}.mp4')
    shutil.copy2(thumb, data / 'thumbs' / f'{key}.jpg')
    store.execute("INSERT OR REPLACE INTO videos (id,title,channel,state,created,media,thumb,bytes,sha256,duration,height,codec) VALUES (?,?,?,'ready',?,?,?,?,?,?,?,?)", (key,'Me at the zoo · проверка передачи '+str(i+1),'YouTube · тест',time.time(),f'{key}.mp4',f'{key}.jpg',sample.stat().st_size,sha256(sample),duration,info['height'],'h264'))

@app.middleware('http')
async def record_transfer(request, call_next):
    if request.url.path.startswith('/api/media/'):
        with (root / 'artifacts/android-transfer.log').open('a') as log:
            log.write(json.dumps({'path':request.url.path, 'range':request.headers.get('range')})+'\n')
    return await call_next(request)

# The integration runner consumes this local file; no production credentials.
private = root / '.private'
private.mkdir(exist_ok=True, mode=0o700)
credential = private / 'android-server-admin'
credential.write_text(app.state.admin_token)
credential.chmod(0o600)
uvicorn.run(app, host='0.0.0.0', port=19473, log_level='warning')
