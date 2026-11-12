"""Isolated visual test library. Never touches the user's real data."""
import hashlib
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "desktop"))
from zapas.store import Store
from zapas.media import Pipeline


def seed(root, sample):
    s = Store(root)
    if s.one("SELECT id FROM videos LIMIT 1"):
        return
    p = Pipeline(s)
    key = p.enqueue(local=str(sample))
    p.process(s.one("SELECT * FROM videos WHERE id=?", (key,)), s.settings())
    original = s.one("SELECT * FROM videos WHERE id=?", (key,))
    if original["state"] != "ready":
        raise RuntimeError(original["error"])
    from PIL import Image, ImageDraw, ImageFont
    names = ["Большой Бак: короткометражный фильм", "Как выглядит мир, когда некуда спешить", "Лес, тишина и один хороший день", "Поездка без связи: всё нужное с собой", "Город с высоты: вечерний маршрут", "Как создают анимацию: от идеи до кадра"]
    palettes = [("#224750", "#e2ba69"), ("#40334f", "#b9d3df"), ("#294a35", "#d7e8b1"), ("#734c37", "#e8cd98"), ("#293d61", "#f7b095"), ("#6b3b4d", "#e6bbd7")]
    font_path = "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf"
    if not Path(font_path).exists():
        font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    font = ImageFont.truetype(font_path, 37) if Path(font_path).exists() else ImageFont.load_default()
    for i, (title, (bg, fg)) in enumerate(zip(names, palettes)):
        k = hashlib.sha256(f"fixture-{i}".encode()).hexdigest()[:24]
        im = Image.new("RGB", (640, 360), bg); draw = ImageDraw.Draw(im)
        draw.ellipse((350, -50, 760, 360), fill=fg)
        draw.polygon([(0,360),(200,180),(400,360)], fill="#172521")
        draw.text((30,38),["КИНО ДОМА", "НЕ СПЕШИ", "ДЕНЬ В ЛЕСУ", "В ДОРОГУ", "ВЕЧЕРНИЙ ГОРОД", "ЗА КАДРОМ"][i],font=font,fill="white")
        draw.text((30,102),"Демонстрационная обложка",fill="white")
        im.save(s.root / "thumbs" / f"{k}.jpg")
        s.execute("INSERT INTO videos (id,title,channel,duration,state,bytes,original_bytes,sha256,thumb,media,height,codec,pinned,created) VALUES (?,?,?,?,'ready',?,?,?,?,?,?,?, ?,?)", (k, title, "Тестовая медиатека", original["duration"],original["bytes"],original["original_bytes"],original["sha256"],f"{k}.jpg",original["media"],original["height"],original["codec"],int(i==2),time.time()-i*3600))
    s.execute("DELETE FROM videos WHERE id=?", (key,))


if __name__ == "__main__":
    seed(Path(sys.argv[1]),Path(sys.argv[2]))
