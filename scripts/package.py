"""Create a source distribution without user data or local credentials."""
from pathlib import Path
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
dist.mkdir(exist_ok=True)
exclude = {".git", ".venv", "node_modules", ".gradle", ".kotlin", "build", ".pytest_cache", "__pycache__", "dist", ".private", "test-library"}
out = dist / "Zapas-0.2.0-source.zip"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=5) as z:
    # Only versioned files belong in a source release, never local diagnostics or credentials.
    paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
    for name in paths:
        if not name:
            continue
        rel = Path(name)
        p = root / rel
        if not p.is_file() or any(x in exclude for x in rel.parts) or p.name == "local.properties" or p.suffix in {".log", ".jks", ".keystore", ".pyc"}:
            continue
        z.write(p, Path("Zapas-0.2.0") / rel)
apk = root / "android/app/build/outputs/apk/debug/app-debug.apk"
if apk.exists():
    shutil.copy2(apk, dist / "Zapas-0.2.0.apk")
print(out)
