#!/usr/bin/env python3
"""Generate Android launcher and notification icons from project icon.png."""

from __future__ import annotations

import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "icon.png"
RES = ROOT / "app" / "src" / "main" / "res"
BACKGROUND = (30, 30, 30, 255)  # #1E1E1E

DENSITY_SCALE = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def fit_icon(source: Image.Image, canvas: int, padding: float = 0.12) -> Image.Image:
    inner = max(1, int(canvas * (1.0 - padding * 2)))
    fitted = ImageOps.contain(source, (inner, inner), Image.Resampling.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    ox = (canvas - fitted.width) // 2
    oy = (canvas - fitted.height) // 2
    out.paste(fitted, (ox, oy), fitted)
    return out


def round_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def apply_round(icon: Image.Image) -> Image.Image:
    size = icon.size[0]
    rounded = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rounded.paste(icon, (0, 0), round_mask(size))
    return rounded


def with_background(icon: Image.Image, bg: tuple[int, int, int, int]) -> Image.Image:
    base = Image.new("RGBA", icon.size, bg)
    base.paste(icon, (0, 0), icon)
    return base


def white_alpha_icon(source: Image.Image, size: int) -> Image.Image:
    fitted = fit_icon(source, size, padding=0.1)
    alpha = fitted.split()[3]
    white = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    white.putalpha(alpha)
    return white


def main() -> None:
    if not SRC.is_file():
        raise SystemExit(f"Missing source icon: {SRC}")

    source = Image.open(SRC).convert("RGBA")
    print(f"Source: {SRC} ({source.size[0]}x{source.size[1]})")

    for density, scale in DENSITY_SCALE.items():
        mipmap = RES / f"mipmap-{density}"
        drawable = RES / f"drawable-{density}"
        ensure_dir(mipmap)
        ensure_dir(drawable)

        launcher = int(48 * scale)
        adaptive = int(108 * scale)
        notif = max(24, int(24 * scale))

        fg = fit_icon(source, adaptive)
        fg.save(mipmap / "ic_launcher_foreground.png", "PNG")

        legacy = with_background(fit_icon(source, launcher), BACKGROUND)
        legacy.save(mipmap / "ic_launcher.png", "PNG")
        apply_round(legacy.copy()).save(mipmap / "ic_launcher_round.png", "PNG")

        white_alpha_icon(source, notif).save(drawable / "ic_notification.png", "PNG")
        white_alpha_icon(source, notif).save(drawable / "ic_stat_connected.png", "PNG")
        white_alpha_icon(source, notif).save(drawable / "ic_stop.png", "PNG")

        mono = int(108 * scale)
        white_alpha_icon(source, mono).save(drawable / "ic_launcher_monochrome.png", "PNG")

        print(f"  {density}: launcher={launcher}, adaptive={adaptive}, notif={notif}")

    # nodpi fallback for notification (some launchers)
    nodpi = RES / "drawable-nodpi"
    ensure_dir(nodpi)
    white_alpha_icon(source, 96).save(nodpi / "ic_notification.png", "PNG")

    print("Done.")


if __name__ == "__main__":
    main()
