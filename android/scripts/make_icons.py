"""
EmuStation のアイコンを作る（Android のアダプティブアイコン・Windows の ico と png）。
  python3 android/scripts/make_icons.py
デザイン：深い青〜紫のグラデーションに光の帯、白いゲームコントローラー、右下に同期（クラウド）のバッジ。
必要なもの：Pillow
"""
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
S = 1024  # 作業の大きさ


def gradient(size: int) -> Image.Image:
    """左上の深い青から、右下の紫へ"""
    img = Image.new("RGBA", (size, size))
    px = img.load()
    a, b = (10, 26, 64), (74, 30, 120)
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * size)
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)
    return img


def waves(size: int) -> Image.Image:
    """XMB のような、やわらかい光の帯"""
    w = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(w)
    for k, (base, amp, thick, alpha) in enumerate([(0.70, 0.05, 0.07, 55), (0.74, 0.04, 0.025, 120), (0.67, 0.06, 0.01, 170)]):
        top, bot = [], []
        for i in range(101):
            x = size * i / 100
            y = size * (base + amp * math.sin(i / 100 * 2 * math.pi * (0.9 + 0.35 * k) + 1.3 * k))
            top.append((x, y))
            bot.append((x, y + size * thick))
        d.polygon(top + bot[::-1], fill=(190, 210, 255, alpha))
    return w.filter(ImageFilter.GaussianBlur(size / 170))


def background(size: int) -> Image.Image:
    return Image.alpha_composite(gradient(size), waves(size))


def controller_mask(size: int) -> Image.Image:
    """コントローラーの形（白＝本体）。十字キーとボタンは穴にする"""
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    u = size / 1024
    cx, cy = 512 * u, 520 * u
    # 本体と、左右のグリップ
    d.rounded_rectangle([cx - 330 * u, cy - 150 * u, cx + 330 * u, cy + 110 * u], radius=150 * u, fill=255)
    for sx in (-1, 1):
        gx = cx + sx * 255 * u
        d.ellipse([gx - 120 * u, cy - 60 * u, gx + 120 * u, cy + 250 * u], fill=255)
    # 十字キー（左）
    dx, dy = cx - 205 * u, cy - 30 * u
    arm, th = 72 * u, 26 * u
    d.rounded_rectangle([dx - arm, dy - th, dx + arm, dy + th], radius=8 * u, fill=0)
    d.rounded_rectangle([dx - th, dy - arm, dx + th, dy + arm], radius=8 * u, fill=0)
    # ボタン（右、ひし形に 4 つ）
    bx, by, r, gap = cx + 205 * u, cy - 30 * u, 27 * u, 62 * u
    for ox, oy in ((0, -gap), (gap, 0), (0, gap), (-gap, 0)):
        d.ellipse([bx + ox - r, by + oy - r, bx + ox + r, by + oy + r], fill=0)
    # スティック（中央の下）
    for sx in (-1, 1):
        x, y, rr = cx + sx * 95 * u, cy + 75 * u, 40 * u
        d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=0)
    return m


def controller(size: int) -> Image.Image:
    """白い本体に、色の付いたボタン・十字キー・スティック。影も付ける"""
    u = size / 1024
    mask = controller_mask(size)
    body = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # 影
    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    shadow.paste((0, 0, 20, 140), (0, 0), mask.filter(ImageFilter.GaussianBlur(18 * u)))
    shadow = ImageChops.offset(shadow, 0, int(18 * u))
    body = Image.alpha_composite(body, shadow)
    # 本体（上が明るい白）
    white = Image.new("RGBA", (size, size))
    wd = ImageDraw.Draw(white)
    for y in range(size):
        t = y / size
        c = int(255 - 30 * t)
        wd.line([(0, y), (size, y)], fill=(c, c, min(255, c + 8), 255))
    body.paste(white, (0, 0), mask)
    # 穴の中：十字キー・スティックは紺、ボタンは 4 色
    d = ImageDraw.Draw(body)
    cx, cy = 512 * u, 520 * u
    dx, dy = cx - 205 * u, cy - 30 * u
    arm, th = 62 * u, 18 * u
    navy = (24, 36, 78, 255)
    d.rounded_rectangle([dx - arm, dy - th, dx + arm, dy + th], radius=6 * u, fill=navy)
    d.rounded_rectangle([dx - th, dy - arm, dx + th, dy + arm], radius=6 * u, fill=navy)
    bx, by, r, gap = cx + 205 * u, cy - 30 * u, 21 * u, 62 * u
    for (ox, oy), col in zip(((0, -gap), (gap, 0), (0, gap), (-gap, 0)),
                             ((92, 214, 255), (255, 92, 120), (98, 226, 140), (255, 200, 70))):
        d.ellipse([bx + ox - r, by + oy - r, bx + ox + r, by + oy + r], fill=col + (255,))
    for sx in (-1, 1):
        x, y, rr = cx + sx * 95 * u, cy + 75 * u, 32 * u
        d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=navy)
        d.ellipse([x - rr * 0.55, y - rr * 0.55, x + rr * 0.55, y + rr * 0.55], fill=(60, 76, 130, 255))
    return body


def sync_badge(size: int, center: tuple[float, float], radius: float) -> Image.Image:
    """同期の印（丸の中に、回る 2 本の矢印）"""
    b = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(b)
    x, y = center
    d.ellipse([x - radius, y - radius, x + radius, y + radius], fill=(40, 200, 255, 255))
    d.ellipse([x - radius, y - radius, x + radius, y + radius], outline=(255, 255, 255, 255), width=int(radius * 0.12))
    rr, w = radius * 0.55, int(radius * 0.16)
    for start in (200, 20):
        d.arc([x - rr, y - rr, x + rr, y + rr], start, start + 130, fill=(255, 255, 255, 255), width=w)
        a = math.radians(start + 130)
        tx, ty = x + rr * math.cos(a), y + rr * math.sin(a)
        # 矢印の先（円の向きに沿った三角）
        ang = a + math.pi / 2
        s = radius * 0.28
        p1 = (tx + s * math.cos(ang), ty + s * math.sin(ang))
        p2 = (tx + s * 0.6 * math.cos(ang + 2.4), ty + s * 0.6 * math.sin(ang + 2.4))
        p3 = (tx + s * 0.6 * math.cos(ang - 2.4), ty + s * 0.6 * math.sin(ang - 2.4))
        d.polygon([p1, p2, p3], fill=(255, 255, 255, 255))
    return b


def foreground(size: int, scale: float) -> Image.Image:
    """背景なしの、コントローラー＋バッジ（scale で全体に対する大きさ）"""
    art = Image.alpha_composite(controller(S), sync_badge(S, (790, 300), 120))
    canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    small = art.resize((int(S * scale), int(S * scale)), Image.LANCZOS)
    off = (S - small.width) // 2
    canvas.alpha_composite(small, (off, off))
    return canvas.resize((size, size), Image.LANCZOS)


def full_icon(size: int, rounded: bool = True) -> Image.Image:
    """Windows 用：角丸の背景＋コントローラー"""
    img = Image.alpha_composite(background(S), foreground(S, 0.86))
    if rounded:
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).rounded_rectangle([24, 24, S - 24, S - 24], radius=210, fill=255)
        out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        img = out
    return img.resize((size, size), Image.LANCZOS)


def poster(w: int = 600, h: int = 800) -> Image.Image:
    """リモートプレイ（Moonlight）のアプリ一覧に出す縦長の絵：背景にコントローラーと「EmuStation」"""
    from PIL import ImageFont
    big = max(w, h)
    bg = background(big).crop(((big - w) // 2, 0, (big - w) // 2 + w, h))
    art = foreground(S, 0.9).resize((int(w * 0.92), int(w * 0.92)), Image.LANCZOS)
    bg.alpha_composite(art, ((w - art.width) // 2, int(h * 0.12)))
    d = ImageDraw.Draw(bg)
    font = None
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
        if Path(f).exists():
            font = ImageFont.truetype(f, int(w * 0.11))
            break
    if font is not None:
        text = "EmuStation"
        tw = d.textlength(text, font=font)
        y = int(h * 0.76)
        d.text(((w - tw) / 2, y + 4), text, font=font, fill=(0, 0, 30, 150))
        d.text(((w - tw) / 2, y), text, font=font, fill=(255, 255, 255, 255))
    return bg


def main():
    # ---- リモートプレイ（Moonlight）のポスター
    poster().convert("RGB").save(ROOT / "desktop/src/main/resources/poster.png", optimize=True)
    # ---- Windows
    full = full_icon(1024)
    full.resize((512, 512), Image.LANCZOS).save(ROOT / "desktop/src/main/resources/icon.png")
    full.save(ROOT / "desktop/packaging/emusync.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    # ---- Android（アダプティブアイコン：108dp。中央の 66dp に収める）
    res = ROOT / "app/src/main/res"
    densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    bg = background(S)
    fg = foreground(S, 0.60)
    # 単色のアイコン（テーマのアイコン）：コントローラーの形だけ（穴あき）
    mono = Image.new("RGBA", (S, S), (255, 255, 255, 0))
    shape = Image.new("L", (S, S), 0)
    small = controller_mask(S).resize((int(S * 0.60), int(S * 0.60)), Image.LANCZOS)
    shape.paste(small, ((S - small.width) // 2, (S - small.height) // 2))
    mono.putalpha(shape)
    for name, k in densities.items():
        px = int(108 * k)
        d = res / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        bg.resize((px, px), Image.LANCZOS).save(d / "ic_launcher_background.png")
        fg.resize((px, px), Image.LANCZOS).save(d / "ic_launcher_foreground.png")
        mono.resize((px, px), Image.LANCZOS).save(d / "ic_launcher_monochrome.png")
    # プレビュー（確かめる用。リポジトリには入れない）
    prev = Image.new("RGBA", (1280, 420), (30, 30, 36, 255))
    prev.alpha_composite(full.resize((360, 360), Image.LANCZOS), (30, 30))
    adaptive = Image.alpha_composite(bg, fg).resize((360, 360), Image.LANCZOS)
    circle = Image.new("L", (360, 360), 0)
    ImageDraw.Draw(circle).ellipse([0, 0, 359, 359], fill=255)
    tmp = Image.new("RGBA", (360, 360), (0, 0, 0, 0))
    tmp.paste(adaptive, (0, 0), circle)
    prev.alpha_composite(tmp, (450, 30))
    for i, s in enumerate((16, 32, 48, 64)):
        prev.alpha_composite(full.resize((s, s), Image.LANCZOS), (880 + i * 90, 200))
    return prev


if __name__ == "__main__":
    import sys
    p = main()
    if len(sys.argv) > 1:
        p.save(sys.argv[1])
