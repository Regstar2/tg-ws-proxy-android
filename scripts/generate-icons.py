#!/usr/bin/env python3
"""Generate versioned app icons from icon.png. Removes ALL legacy icon files."""

from __future__ import annotations

from collections import deque
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "icon.png"
RES = ROOT / "app" / "src" / "main" / "res"
BACKGROUND = (30, 30, 30, 255)

DENSITY_SCALE = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

# Every obsolete filename — must not ship in APK.
LEGACY_FILENAMES = {
    "ic_launcher.png",
    "ic_launcher_round.png",
    "ic_launcher_foreground.png",
    "ic_launcher_monochrome.png",
    "ic_notification.png",
    "ic_notification_large.png",
    "ic_notification_small.png",
    "notification_app_icon.png",
    "ic_stat_connected.png",
    "ic_stop.png",
}

LEGACY_XML_NAMES = {
    "ic_launcher.xml",
    "ic_launcher_round.xml",
    "ic_notification.xml",
}

FOREGROUND_NAME = "ic_launcher_tgwsproxy_foreground_v2.png"
MONOCHROME_NAME = "ic_launcher_tgwsproxy_monochrome_v2.png"
NOTIFICATION_LARGE_NAME = "notification_app_icon_v2.png"


def ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def remove_legacy_icons() -> int:
    removed = 0
    if not RES.is_dir():
        return 0
    for path in RES.rglob("*"):
        if not path.is_file():
            continue
        if path.name in LEGACY_FILENAMES or path.name in LEGACY_XML_NAMES:
            path.unlink()
            print(f"  removed {path.relative_to(ROOT)}")
            removed += 1
    return removed


def fit_icon(source: Image.Image, canvas: int, padding: float = 0.12) -> Image.Image:
    inner = max(1, int(canvas * (1.0 - padding * 2)))
    fitted = ImageOps.contain(source, (inner, inner), Image.Resampling.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    ox = (canvas - fitted.width) // 2
    oy = (canvas - fitted.height) // 2
    out.paste(fitted, (ox, oy), fitted)
    return out


def remove_near_black_background(img: Image.Image, threshold: int = 36) -> Image.Image:
    img = img.convert("RGBA")
    pixels = img.load()
    w, h = img.size
    visited = [[False] * w for _ in range(h)]

    def is_border_black(x: int, y: int) -> bool:
        r, g, b, a = pixels[x, y]
        return a > 0 and r <= threshold and g <= threshold and b <= threshold

    q: deque[tuple[int, int]] = deque()
    for x in range(w):
        for y in (0, h - 1):
            if is_border_black(x, y) and not visited[y][x]:
                q.append((x, y))
                visited[y][x] = True
    for y in range(h):
        for x in (0, w - 1):
            if is_border_black(x, y) and not visited[y][x]:
                q.append((x, y))
                visited[y][x] = True

    while q:
        x, y = q.popleft()
        pixels[x, y] = (0, 0, 0, 0)
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h and not visited[ny][nx] and is_border_black(nx, ny):
                visited[ny][nx] = True
                q.append((nx, ny))

    return img


def monochrome_mask(source: Image.Image, size: int) -> Image.Image:
    fitted = fit_icon(source, size, padding=0.06)
    mask = Image.new("L", (size, size), 0)
    px = fitted.load()
    for y in range(size):
        for x in range(size):
            if px[x, y][3] > 24:
                mask.putpixel((x, y), 255)
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.putalpha(mask)
    return out


def write_adaptive_xml(path: Path, round_variant: bool) -> None:
    name = "ic_launcher_tgwsproxy_round_v2" if round_variant else "ic_launcher_tgwsproxy_v2"
    path.write_text(
        f"""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_tgwsproxy_foreground_v2" />
    <monochrome android:drawable="@drawable/{MONOCHROME_NAME.replace('.png', '')}" />
</adaptive-icon>
""",
        encoding="utf-8",
    )
    print(f"  wrote {path.relative_to(ROOT)}")


def main() -> None:
    if not SRC.is_file():
        raise SystemExit(f"Missing source icon: {SRC}")

    print("Removing legacy icon files...")
    removed = remove_legacy_icons()
    print(f"  removed {removed} file(s)")

    source = remove_near_black_background(Image.open(SRC).convert("RGBA"))
    print(f"Source: {SRC} ({source.size[0]}x{source.size[1]})")

    nodpi = RES / "drawable-nodpi"
    ensure_dir(nodpi)
    monochrome_mask(source, 108).save(nodpi / MONOCHROME_NAME, "PNG")
    fit_icon(source, 512, padding=0.06).save(nodpi / NOTIFICATION_LARGE_NAME, "PNG")
    print(f"  nodpi: {MONOCHROME_NAME}, {NOTIFICATION_LARGE_NAME}")

    for density, scale in DENSITY_SCALE.items():
        mipmap = RES / f"mipmap-{density}"
        ensure_dir(mipmap)
        adaptive = int(108 * scale)
        fit_icon(source, adaptive).save(mipmap / FOREGROUND_NAME, "PNG")
        print(f"  {density}: {FOREGROUND_NAME}={adaptive}")

    anydpi = RES / "mipmap-anydpi-v26"
    ensure_dir(anydpi)
    write_adaptive_xml(anydpi / "ic_launcher_tgwsproxy_v2.xml", round_variant=False)
    write_adaptive_xml(anydpi / "ic_launcher_tgwsproxy_round_v2.xml", round_variant=True)

    print("Done.")


if __name__ == "__main__":
    main()
